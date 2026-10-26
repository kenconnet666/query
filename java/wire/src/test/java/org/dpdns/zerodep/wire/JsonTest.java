package org.dpdns.zerodep.wire;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Json.read：对象保持键顺序、整数是 Long、其余数字是 BigDecimal、转义与代理对、写错时报错并带位置、嵌套层数的上限；Json.write 与读互逆。 */
class JsonTest {
    @Test
    void readsObjectsArraysAndScalars() {
        Object tree = Json.read(" {\"any\":[{\"field\":\"amount\",\"op\":\"gte\",\"value\":10000},{\"not\":{\"field\":\"urgent\",\"op\":\"eq\",\"value\":false}}],\"n\":null} ");
        Map<String, Object> expected = new LinkedHashMap<>();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("field", "amount");
        rule.put("op", "gte");
        rule.put("value", 10000L);
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("field", "urgent");
        inner.put("op", "eq");
        inner.put("value", false);
        expected.put("any", List.of(rule, Map.of("not", inner)));
        expected.put("n", null);
        assertEquals(expected, tree);
        assertEquals(List.of("any", "n"), List.copyOf(((Map<?, ?>) tree).keySet()));
    }

    @Test
    void numbersKeepPrecision() {
        assertEquals(List.of(-12L, new BigDecimal("12.50"), new BigDecimal("1E+3"), new BigDecimal("9223372036854775808")),
            Json.read("[-12, 12.50, 1e3, 9223372036854775808]"));
    }

    @Test
    void escapesAndUnicode() throws Exception {
        assertEquals("a\"b\\c/\n\t 中 😀", Json.read("\"a\\\"b\\\\c\\/\\n\\t \\u4e2d \\ud83d\\ude00\""));
        // 与 Jackson 读出的相同
        String text = "{\"s\":\"\\u00e9\\b\\f\\r\",\"x\":[true,false,null,0,-0.5]}";
        assertEquals(Arrays.asList(true, false, null, 0L, new BigDecimal("-0.5")), ((Map<?, ?>) Json.read(text)).get("x"));
        assertEquals(new ObjectMapper().readTree(text).get("s").asText(), ((Map<?, ?>) Json.read(text)).get("s"));
    }

    @Test
    void rejectsBadInputWithPosition() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\" 1}", "{a:1}", "01", "1.", "-", "\"\\x\"", "\"a", "tru", "[1] 2", "\"\u0001\""}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.read(bad), bad);
            assertTrue(e.getMessage().startsWith("Json.read: "), e.getMessage());
            assertTrue(e.getMessage().contains("at position"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Json.read(null));
        assertNull(Json.read(" null "));
    }

    @Test
    void limitsNesting() {
        String deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.read(deep));
        assertTrue(e.getMessage().contains("nested more than"), e.getMessage());
        String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
        assertTrue(Json.read(ok) instanceof List<?>);
    }

    @Test
    void writesWhatItReadsAndWhatJacksonReads() throws Exception {
        String text = "{\"any\":[{\"field\":\"amount\",\"op\":\"gte\",\"value\":10000.50}],\"s\":\"a\\\"b\\\\c\\n\\t\\u0001 中\",\"n\":null,\"b\":[true,false,-12]}";
        String written = Json.write(Json.read(text));
        assertEquals(Json.read(text), Json.read(written));
        assertEquals(new ObjectMapper().readTree(text), new ObjectMapper().readTree(written));
        assertEquals("[1000,1000,0.5]", Json.write(List.of(new BigDecimal("1E+3"), 1000L, 0.5)));
        assertEquals("{\"k\":[\"x\"]}", Json.write(Map.of("k", new String[] {"x"})));
    }

    @Test
    void refusesWhatJsonCannotHold() {
        for (Object bad : new Object[] {Double.NaN, Float.POSITIVE_INFINITY, new Object(), List.of(java.time.LocalDate.of(2026, 1, 1))}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.write(bad), String.valueOf(bad));
            assertTrue(e.getMessage().startsWith("Json.write: "), e.getMessage());
        }
    }
}
