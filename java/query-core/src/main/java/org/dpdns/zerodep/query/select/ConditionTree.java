package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.wire.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 条件树：前端条件构造器（vue-tsx-pro 的 {@code ConditionBuilder}、FilterBar 的高级筛选）编辑的条件，JSON 原样传来（0017）：
 * <pre>
 * {"all": [ {"field": "amount", "op": "gte", "value": 10000},
 *           {"any": [ {"field": "category", "op": "in", "value": ["steel", "aluminum"]},
 *                     {"not": {"field": "urgent", "op": "eq", "value": false}} ]} ]}
 * </pre>
 * 交给 {@link Query#conditionBy}：字段是结果列名（白名单），值按列的 Java 类型读。与前端的 {@code matchesCondition} 求值一致：
 * 不等于、不在其中、不包含把空值算作满足；没填完整的比较（值为空、区间两端都空、空集合）和空的组不加条件。
 * <pre>{@code
 * @PostMapping("/orders/search")
 * Object search(@RequestBody Map<String, Object> condition) {
 *     return db.list(orders().conditionBy(ConditionTree.from(condition)));
 * }
 * }</pre>
 * 地址栏里的 {@code condition} 参数由 {@link org.dpdns.zerodep.query.exec.TableRequest#parse} 读成它。
 */
public sealed interface ConditionTree permits ConditionTree.Rule, ConditionTree.All, ConditionTree.Any, ConditionTree.Not {
    /** 嵌套的上限：防止恶意的深层 JSON 把栈压爆。 */
    int MAX_DEPTH = 32;

    /** 比较的方式，线上的名字同 vue-tsx-pro 的 {@code ConditionOp}。 */
    enum Op {
        /** 等于 */
        EQ("eq"),
        /** 不等于（空值算作满足） */
        NE("ne"),
        /** 大于 */
        GT("gt"),
        /** 大于等于 */
        GTE("gte"),
        /** 小于 */
        LT("lt"),
        /** 小于等于 */
        LTE("lte"),
        /** 在两个值之间（含两端），值是两项的集合 */
        BETWEEN("between"),
        /** 是其中之一，值是集合 */
        IN("in"),
        /** 不是其中任何一个（空值算作满足），值是集合 */
        NOT_IN("notIn"),
        /** 文字包含 */
        CONTAINS("contains"),
        /** 文字不包含（空值算作满足） */
        NOT_CONTAINS("notContains"),
        /** 文字以它开头 */
        STARTS_WITH("startsWith"),
        /** 有值（文字列不算空字符串） */
        EXISTS("exists"),
        /** 没有值（文字列含空字符串） */
        EMPTY("empty");

        /** 线上的名字。 */
        public final String wireName;

        Op(String wireName) { this.wireName = wireName; }

        /** 按线上的名字找；不认识时报错并列出可用的。 */
        public static Op of(String name, String at) {
            for (Op op : values()) if (op.wireName.equals(name)) return op;
            List<String> names = new ArrayList<>();
            for (Op op : values()) names.add(op.wireName);
            throw new IllegalArgumentException(at + ": unknown operator \"" + name + "\"; use one of " + String.join(", ", names));
        }
    }

    /** 比较一个结果列；value 是 JSON 读出的值（文字、数字、布尔、集合），exists、empty 没有值。 */
    record Rule(String field, Op op, Object value) implements ConditionTree {
        /** 校验字段名与比较方式。 */
        public Rule {
            if (field == null || field.isBlank()) throw new IllegalArgumentException("condition rule needs a field");
            Objects.requireNonNull(op, "op");
        }
    }

    /** 全部满足（AND）；空的不加条件。 */
    record All(List<ConditionTree> items) implements ConditionTree {
        /** 复制成不可变的列表。 */
        public All { items = List.copyOf(items); }
    }

    /** 任一满足（OR）；空的不加条件。 */
    record Any(List<ConditionTree> items) implements ConditionTree {
        /** 复制成不可变的列表。 */
        public Any { items = List.copyOf(items); }
    }

    /** 不满足（NOT）。 */
    record Not(ConditionTree item) implements ConditionTree {
        /** 不能为 null。 */
        public Not { Objects.requireNonNull(item, "item"); }
    }

    /** 读 JSON 文字（地址栏参数）。写错时报 IllegalArgumentException，消息里带出错的位置（{@code condition.all[1].op}）。 */
    static ConditionTree parse(String json) { return from(Json.read(json)); }

    /**
     * 读已经解析好的 JSON 树：Jackson、Gson 读出的 Map 与 List，或 {@link Json#read} 的结果。
     * 形状不对、比较方式不认识时报 IllegalArgumentException（Web 层据此回 400）。
     */
    static ConditionTree from(Object json) { return read(json, "condition", 0); }

    /** 条件树里用到的字段名（去重，按出现的先后）。 */
    default Set<String> fields() {
        Set<String> names = new LinkedHashSet<>();
        collect(this, names);
        return names;
    }

    private static void collect(ConditionTree node, Set<String> names) {
        if (node instanceof Rule r) names.add(r.field());
        else if (node instanceof All a) a.items().forEach(item -> collect(item, names));
        else if (node instanceof Any a) a.items().forEach(item -> collect(item, names));
        else collect(((Not) node).item(), names);
    }

    private static ConditionTree read(Object node, String at, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException(at + ": the condition is nested more than " + MAX_DEPTH + " levels");
        if (!(node instanceof Map<?, ?> map)) throw new IllegalArgumentException(at + ": expected an object");
        for (String key : List.of("all", "any")) {
            if (!map.containsKey(key)) continue;
            if (!(map.get(key) instanceof Collection<?> list)) throw new IllegalArgumentException(at + "." + key + ": expected an array");
            List<ConditionTree> items = new ArrayList<>();
            int i = 0;
            for (Object child : list) items.add(read(child, at + "." + key + "[" + i++ + "]", depth + 1));
            return key.equals("all") ? new All(items) : new Any(items);
        }
        if (map.containsKey("not")) return new Not(read(map.get("not"), at + ".not", depth + 1));
        if (!(map.get("field") instanceof String field) || field.isBlank()) throw new IllegalArgumentException(at + ".field: expected a column name");
        if (!(map.get("op") instanceof String op)) throw new IllegalArgumentException(at + ".op: expected an operator name");
        return new Rule(field, Op.of(op, at + ".op"), map.get("value"));
    }
}
