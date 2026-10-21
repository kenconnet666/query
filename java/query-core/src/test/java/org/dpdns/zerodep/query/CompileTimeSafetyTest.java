package org.dpdns.zerodep.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generic design promises these mistakes do not compile; javac checks the promise on every build. */
@DisplayName("编译期类型检查")
class CompileTimeSafetyTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        用了作用域外的表的getter     | from(Lips.class).where(l -> l.col(Makt::getText).eq("x"));
        比较值类型和列类型不符       | from(Lips.class).where(l -> l.col(Lips::getQuantity).eq("x"));
        连接键类型不符               | from(Lips.class).join(Mara.class, (l, ma) -> ma.col(Mara::getClient).eq(l, Lips::getQuantity));
        COALESCE的两个参数类型不符   | from(Lips.class).selectValue(l -> l.col(Lips::getQuantity).coalesce(l.col(Lips::getUnit)));
        对文本列求和                 | from(Lips.class).selectValue(l -> sum(l.col(Lips::getUnit)));
        投影构造器参数类型不符       | from(Lips.class).select((r, l) -> new Delivery(r.get(l, Lips::getQuantity), "x", null));
        别名指向类型不同的属性       | from(Lips.class).selectValue(l -> sum(l.col(Lips::getQuantity)).as(DeliveryVO::getName));
        UNION两段的结果类型不同      | Scenarios.stock("2026").unionAll(Scenarios.lines());
        套用泛型模板的表缺少共享形状 | Scenarios.stockArm(Lips.class, h -> h.col(Lips::getQuantity), "2026");
        日期列传入字符串             | from(Lips.class).where(l -> l.col(Lips::getCreatedOn).between("20260101", "20260131"));
        窗口函数缺少OVER             | from(Lips.class).selectValue(l -> rowNumber());
        """)
    void 错误写法不能编译(String name, String statement) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = compile(statement);
        assertFalse(diagnostics.getDiagnostics().isEmpty(), name + " compiled, but it should not");
    }

    @Test
    void 正确写法可以编译() throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = compile(
            "from(Lips.class).join(Mara.class, (l, ma) -> ma.col(Mara::getMaterial).eq(l, Lips::getMaterial))"
                + ".select((r, l, ma) -> new Delivery(r.get(ma, Mara::getPartNumber), r.get(l, Lips::getUnit), r.get(sum(l.col(Lips::getQuantity)))));");
        assertTrue(diagnostics.getDiagnostics().isEmpty(), diagnostics.getDiagnostics().toString());
    }

    private static DiagnosticCollector<JavaFileObject> compile(String statement) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests need a JDK, not a JRE");
        String source = """
            import org.dpdns.zerodep.query.testing.Scenarios;
            import org.dpdns.zerodep.query.testing.Erp.*;
            import static org.dpdns.zerodep.query.Sql.*;
            class Snippet { void run() { %s } }
            """.formatted(statement);
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Snippet.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        Path out = Files.createTempDirectory("snippet");
        String classpath = Path.of("target", "classes").toAbsolutePath() + File.pathSeparator + Path.of("target", "test-classes").toAbsolutePath();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        javac.getTask(null, null, diagnostics, List.of("-classpath", classpath, "-d", out.toString(), "-proc:none"), null, List.of(file)).call();
        return diagnostics;
    }
}
