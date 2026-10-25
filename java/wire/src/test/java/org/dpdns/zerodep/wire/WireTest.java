package org.dpdns.zerodep.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Line(String sku, BigDecimal qty) { }

    /** spec/wire/order.json 里的对象；TypeScript 的 wire.test.ts 用普通对象写同一个。 */
    record Order(Long id, String code, int count, double rate, double ratio, boolean active, BigDecimal amount, LocalDate on, LocalTime at,
                 LocalDateTime created, Instant sent, byte[] file, String note, List<Line> lines, List<Long> tags, Map<String, BigDecimal> byMonth) { }

    private static Order order() {
        Map<String, BigDecimal> byMonth = new LinkedHashMap<>();
        byMonth.put("2024.01", new BigDecimal("3.5"));
        byMonth.put("2024.02", BigDecimal.ZERO);
        return new Order(9007199254740993L, "PO-1", 3, 0.25, Double.NaN, true, new BigDecimal("12.5"), LocalDate.of(2024, 1, 31),
            LocalTime.of(13, 45), LocalDateTime.of(2024, 1, 31, 13, 45, 0, 500_000_000), Instant.parse("2024-01-31T05:45:00Z"),
            new byte[] {1, 2, 3, (byte) 255}, null, List.of(new Line("A.1", new BigDecimal("2.75")), new Line("B", BigDecimal.ONE)),
            List.of(1L, 2L), byMonth);
    }

    /** 仓库根目录下的共用样例。 */
    private static JsonNode sample() throws Exception {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(p.resolve("spec").resolve("wire"))) p = p.getParent();
        return JSON.readTree(p.resolve("spec/wire/order.json").toFile());
    }

    @Test
    void 编码结果经Jackson写出后与共用样例相同() throws Exception {
        assertEquals(sample(), JSON.readTree(JSON.writeValueAsString(Wire.encode(order()))));
    }

    @Test
    void 没有要还原的值时是普通的JSON树_根不是对象时包成value() {
        assertEquals(Map.of("name", "a", "n", 1), Wire.encode(Map.of("name", "a", "n", 1)));
        assertEquals(Map.of("$value", List.of("1", "2"), "$types", Map.of("$value", "bigint")), Wire.encode(List.of(1L, 2L)));
        assertEquals(Map.of("$value", "1000000000000000000000", "$types", Map.of("$value", "decimal")), Wire.encode(new BigDecimal("1E+21")));
    }

    /** 普通的类：公开 getter，布尔用 isX，字段名决定属性名的写法。 */
    public static final class Bean {
        private final String URL = "https://example.test";
        private final boolean open = true;
        private final Optional<OffsetDateTime> due = Optional.of(OffsetDateTime.of(2024, 1, 31, 13, 45, 0, 0, ZoneOffset.ofHours(8)));
        public final BigInteger big = BigInteger.TEN.pow(20);

        public String getURL() { return URL; }
        public boolean isOpen() { return open; }
        public Optional<OffsetDateTime> getDue() { return due; }
        public Thread.State getState() { return Thread.State.NEW; }
    }

    @Test
    void 普通的类取公开getter与公开字段() {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("URL", "https://example.test");
        expected.put("open", true);
        expected.put("due", "2024-01-31T05:45:00Z");
        expected.put("big", "100000000000000000000");
        expected.put("state", "NEW");
        expected.put("$types", Map.of("due", "timestamptz", "big", "bigint"));
        assertEquals(expected, Wire.encode(new Bean()));
    }

    @Test
    void 同一路径只能是一种值_循环引用与保留的键报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> Wire.encode(List.of(Map.of("v", 1L), Map.of("v", "x"))));
        assertTrue(e.getMessage().contains("values at v are both bigint and text"), e.getMessage());
        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(loop));
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(Map.of("$types", Map.of())));
    }

    @Test
    void text与parse是地址栏里的单个值() {
        assertEquals("0.0000001", Wire.text(new BigDecimal("1E-7")));
        assertEquals("2024-01-31T05:45:00.12Z", Wire.text(Instant.parse("2024-01-31T05:45:00.120Z")));
        assertEquals("2024-01-31T13:45:00.5", Wire.text(LocalDateTime.of(2024, 1, 31, 13, 45, 0, 500_000_000)));
        assertEquals("0.25", Wire.text(0.25));
        assertEquals("", Wire.text(null));
        assertThrows(IllegalArgumentException.class, () -> Wire.text(List.of()));

        assertEquals(-12, Wire.parse(int.class, "-12"));
        assertEquals(9007199254740993L, Wire.parse(Long.class, "9007199254740993"));
        assertEquals(new BigDecimal("12.50"), Wire.parse(BigDecimal.class, "12.50"));
        assertEquals(LocalDateTime.of(2024, 1, 31, 13, 45), Wire.parse(LocalDateTime.class, "2024-01-31 13:45"));
        assertEquals(Instant.parse("2024-01-31T05:45:00Z"), Wire.parse(Instant.class, "2024-01-31T13:45:00+08:00"));
        assertEquals(Thread.State.NEW, Wire.parse(Thread.State.class, "NEW"));
        assertArrayEquals(new byte[] {1, 2, 3, (byte) 255}, Wire.parse(byte[].class, "AQID/w=="));
        for (String[] bad : new String[][] {{"Integer", "1.5"}, {"LocalDate", "2023-02-29"}, {"Boolean", "yes"}, {"BigDecimal", "NaN"}}) {
            Class<?> type = switch (bad[0]) {
                case "Integer" -> Integer.class;
                case "LocalDate" -> LocalDate.class;
                case "Boolean" -> Boolean.class;
                default -> BigDecimal.class;
            };
            var error = assertThrows(IllegalArgumentException.class, () -> Wire.parse(type, bad[1]));
            assertTrue(error.getMessage().contains("\"" + bad[1] + "\""), error.getMessage());
        }
    }
}
