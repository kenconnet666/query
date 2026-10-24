package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.testing.Erp.Client;
import org.dpdns.zerodep.query.write.Write;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden SQL shared with the TypeScript tests. {@code spec/render/<case>.<dialect>.sql} holds the
 * parameters (as strings, in a JSON array) on its first line and the statement after it. Both
 * languages must render every file exactly; {@code QUERY_SPEC_WRITE=1} rewrites the files from Java.
 */
public abstract class RenderSpec {
    /** One case: a query or a single-statement write, rendered by a database with or without the live scenarios' client filter. */
    public record SpecCase(boolean filtered, Supplier<?> query) {
        public static SpecCase plain(Supplier<?> query) { return new SpecCase(false, query); }

        public static SpecCase filtered(Supplier<?> query) { return new SpecCase(true, query); }
    }

    private static final String HEADER = "-- parameters: ";

    private static final ConnectionProvider NO_CONNECTIONS = new ConnectionProvider() {
        @Override public Connection acquire() throws SQLException { throw new SQLException("Spec rendering never connects"); }

        @Override public void release(Connection connection) { }
    };

    /** The file suffix: hana, postgres or mysql. */
    protected abstract String dialect();

    protected abstract Database database(ConnectionProvider connections, Consumer<Database.Config> configure);

    /** Cases only this dialect renders. */
    protected Map<String, SpecCase> dialectCases() { return Map.of(); }

    private Map<String, SpecCase> cases() {
        Map<String, SpecCase> all = new LinkedHashMap<>(SpecCases.shared());
        all.putAll(dialectCases());
        return all;
    }

    @TestFactory
    Stream<DynamicTest> 渲染结果与金样例一致() {
        Database filtered = database(NO_CONNECTIONS, c -> c.filter(Client.class, r -> r.client().eq("100")));
        Database plain = database(NO_CONNECTIONS, null);
        boolean write = System.getenv("QUERY_SPEC_WRITE") != null;
        return cases().entrySet().stream().map(e -> DynamicTest.dynamicTest(dialect() + " 渲染 " + e.getKey() + " 与金样例一致", () -> {
            Database db = e.getValue().filtered() ? filtered : plain;
            Object statement = e.getValue().query().get();
            Rendered r;
            if (statement instanceof Write w) {
                List<Rendered> all = db.render(w);
                assertEquals(1, all.size(), "a write case renders one statement");
                r = all.get(0);
            } else {
                r = db.render((Query<?>) statement);
            }
            // 数组参数写成逗号连接的元素，与 TypeScript 的 String(array) 相同。
            List<String> parameters = r.parameters().stream().map(v -> v instanceof Object[] a
                ? java.util.Arrays.stream(a).map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) : String.valueOf(v)).toList();
            Path file = directory().resolve(e.getKey() + "." + dialect() + ".sql");
            if (write) {
                Files.writeString(file, HEADER + json(parameters) + "\n" + r.sql() + "\n", StandardCharsets.UTF_8);
                return;
            }
            assertTrue(Files.exists(file), file + " is missing; run with QUERY_SPEC_WRITE=1 to write it");
            String content = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            int newline = content.indexOf('\n');
            assertTrue(content.startsWith(HEADER), file + " starts with its parameters");
            assertEquals(content.substring(newline + 1).stripTrailing(), r.sql());
            assertEquals(parseJson(content.substring(HEADER.length(), newline)), parameters);
        }));
    }

    @Test
    void 每个金样例在Java中都有对应的查询() throws IOException {
        String suffix = "." + dialect() + ".sql";
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory())) {
            files.map(p -> p.getFileName().toString()).filter(f -> f.endsWith(suffix))
                .map(f -> f.substring(0, f.length() - suffix.length())).filter(name -> !cases().containsKey(name)).forEach(missing::add);
        }
        assertEquals(List.of(), missing);
    }

    /** spec/render at the repository root, found from the module directory Maven runs tests in. */
    private static Path directory() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("spec"))) {
                Path render = p.resolve("spec").resolve("render");
                try {
                    return Files.createDirectories(render);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalStateException("No spec directory above " + Path.of("").toAbsolutePath());
    }

    private static String json(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(",");
            out.append('"');
            for (char c : values.get(i).toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }
        return out.append("]").toString();
    }

    /** Reads the JSON array of strings {@link #json} writes. */
    private static List<String> parseJson(String text) {
        List<String> out = new ArrayList<>();
        String s = text.strip();
        if (!s.startsWith("[") || !s.endsWith("]")) throw new IllegalArgumentException("Not a JSON array: " + text);
        int i = 1;
        while (i < s.length() - 1) {
            char c = s.charAt(i);
            if (c == ',' || c == ' ') {
                i++;
                continue;
            }
            if (c != '"') throw new IllegalArgumentException("Expected a string at " + i + " in " + text);
            StringBuilder value = new StringBuilder();
            for (i++; s.charAt(i) != '"'; i++) {
                char ch = s.charAt(i);
                if (ch != '\\') {
                    value.append(ch);
                    continue;
                }
                char escaped = s.charAt(++i);
                switch (escaped) {
                    case 'n' -> value.append('\n');
                    case 'u' -> {
                        value.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> value.append(escaped);
                }
            }
            out.add(value.toString());
            i++;
        }
        return out;
    }
}
