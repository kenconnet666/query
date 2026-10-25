package org.dpdns.zerodep.wire;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读 JSON 文字（RFC 8259）成 Map、List、String、数字、Boolean 和 null，不依赖哪一个 JSON 库：给地址栏里带 JSON 的参数用
 * （表格请求的 {@code condition}），请求体照常交给 Web 框架的 JSON 库。对象是保持键顺序的 Map，数字是整数且放得进 long 时为 Long，
 * 其余为 BigDecimal（不经 double，不丢精度）。读出来的 Map、List 不可修改。
 * <pre>{@code
 * Object tree = Json.read("{\"any\":[{\"field\":\"amount\",\"op\":\"gte\",\"value\":10000}]}");
 * }</pre>
 * 写错时报 IllegalArgumentException，消息里带上出错的位置；嵌套超过 {@link #MAX_DEPTH} 层也报错（不让深层的输入压爆栈）。
 */
public final class Json {
    /** 对象、数组最多嵌套几层。 */
    public static final int MAX_DEPTH = 256;

    private final String text;
    private int at;

    private Json(String text) { this.text = text; }

    /** 读一段 JSON 文字；前后可以有空白，之后不能再有别的内容。 */
    public static Object read(String text) {
        if (text == null) throw new IllegalArgumentException("Json.read: text is null");
        Json reader = new Json(text);
        Object value = reader.value(0);
        reader.space();
        if (reader.at < text.length()) throw reader.error("unexpected content after the JSON value");
        return value;
    }

    /** 一个值。 */
    private Object value(int depth) {
        space();
        if (at >= text.length()) throw error("unexpected end of JSON");
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        if (depth > MAX_DEPTH) throw error("JSON is nested more than " + MAX_DEPTH + " levels");
        at++;
        Map<String, Object> out = new LinkedHashMap<>();
        space();
        if (peek('}')) return Collections.unmodifiableMap(out);
        while (true) {
            space();
            if (!peekChar('"')) throw error("expected a property name");
            String key = string();
            space();
            expect(':');
            out.put(key, value(depth));
            space();
            if (peek('}')) return Collections.unmodifiableMap(out);
            expect(',');
        }
    }

    private List<Object> array(int depth) {
        if (depth > MAX_DEPTH) throw error("JSON is nested more than " + MAX_DEPTH + " levels");
        at++;
        List<Object> out = new ArrayList<>();
        space();
        if (peek(']')) return Collections.unmodifiableList(out);
        while (true) {
            out.add(value(depth));
            space();
            if (peek(']')) return Collections.unmodifiableList(out);
            expect(',');
        }
    }

    private String string() {
        at++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw error("unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error("control character in a string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) throw error("unterminated escape");
            char e = text.charAt(at++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) throw error("short \\u escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    at += 4;
                }
                default -> throw error("unknown escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = at;
        if (peekChar('-')) at++;
        if (peekChar('0')) at++;
        else digits();
        boolean whole = true;
        if (peekChar('.')) {
            at++;
            digits();
            whole = false;
        }
        if (peekChar('e') || peekChar('E')) {
            at++;
            if (peekChar('+') || peekChar('-')) at++;
            digits();
            whole = false;
        }
        String n = text.substring(start, at);
        if (whole) {
            try {
                return Long.valueOf(n);
            } catch (NumberFormatException ignored) {
                // 放不进 long 的整数用 BigDecimal
            }
        }
        return new BigDecimal(n);
    }

    /** 至少一位数字。 */
    private void digits() {
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at)) && text.charAt(at) < 128) at++;
        if (at == start) throw error("expected a digit");
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) throw error("unexpected token");
        at += word.length();
        return value;
    }

    private void space() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
            at++;
        }
    }

    /** 下一个字符是 c 时跳过它。 */
    private boolean peek(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private boolean peekChar(char c) { return at < text.length() && text.charAt(at) == c; }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Json.read: " + message + " at position " + at);
    }
}
