package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.scope.SkipEmpty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** 条件树（{@link ConditionTree}）写成条件：{@link Query#conditionBy} 用；值按列的类型读，比较规则同前端的 matchesCondition。 */
final class TreeConditions {
    /** 只能用在文字列上的比较。 */
    private static final Set<ConditionTree.Op> TEXT_OPS = Set.of(ConditionTree.Op.CONTAINS, ConditionTree.Op.NOT_CONTAINS, ConditionTree.Op.STARTS_WITH);

    /** 工具类，不能实例化。 */
    private TreeConditions() { }

    /** 条件树写成条件；column 按字段名给出结果列的表达式。 */
    static Cond of(ConditionTree tree, Function<String, Expr<?>> column) {
        if (tree instanceof ConditionTree.All a) return Nodes.all(a.items().stream().map(item -> of(item, column)).toList());
        if (tree instanceof ConditionTree.Any a) return Nodes.any(a.items().stream().map(item -> of(item, column)).toList());
        if (tree instanceof ConditionTree.Not n) return Nodes.not(of(n.item(), column));
        ConditionTree.Rule r = (ConditionTree.Rule) tree;
        return rule(r, column.apply(r.field()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Cond rule(ConditionTree.Rule r, Expr c) {
        String at = "condition '" + r.field() + "'";
        boolean text = c.type().javaType() == String.class;
        if (TEXT_OPS.contains(r.op()) && !text) throw new IllegalArgumentException(at + ": " + r.op().wireName + " needs a text column");
        Object value = r.value();
        switch (r.op()) {
            case EXISTS:
                return Conditions.collect(() -> {
                    c.isNotNull();
                    if (text) c.ne("");
                });
            case EMPTY:
                return text ? Nodes.any(List.of(Conditions.collect(c::isNull), Conditions.collect(() -> c.eq(""))))
                    : Conditions.collect(c::isNull);
            case BETWEEN: {
                if (value == null) return Cond.EMPTY;
                if (!(value instanceof Collection<?> ends) || ends.size() != 2) throw new IllegalArgumentException(at + ": between needs two values [low, high]");
                List<Object> bounds = new ArrayList<>();
                for (Object end : ends) bounds.add(blank(end) ? null : scalar(end));
                // 空的一端交给 skipEmpty 省略（同条件对象的区间）
                return SkipEmpty.within(true, () -> Conditions.collect(() -> FilterObject.range(c, bounds, at)));
            }
            case IN:
            case NOT_IN: {
                if (value == null) return Cond.EMPTY;
                if (!(value instanceof Collection<?> items)) throw new IllegalArgumentException(at + ": " + r.op().wireName + " needs an array");
                List<Object> values = new ArrayList<>();
                for (Object item : items) if (!blank(item)) values.add(FilterObject.textAs(c, scalar(item)));
                if (values.isEmpty()) return Cond.EMPTY;
                return r.op() == ConditionTree.Op.IN ? Conditions.collect(() -> c.in(values)) : orNull(c, Conditions.collect(() -> c.notIn(values)));
            }
            default:
                break;
        }
        if (blank(value)) return Cond.EMPTY;
        if (value instanceof Collection<?> || value instanceof java.util.Map<?, ?>) throw new IllegalArgumentException(at + ": " + r.op().wireName + " needs a single value");
        String words = scalar(value).toString();
        Object v = FilterObject.textAs(c, scalar(value));
        return switch (r.op()) {
            case EQ -> Conditions.collect(() -> c.eq(v));
            case NE -> orNull(c, Conditions.collect(() -> c.ne(v)));
            case GT -> Conditions.collect(() -> c.gt(v));
            case GTE -> Conditions.collect(() -> c.ge(v));
            case LT -> Conditions.collect(() -> c.lt(v));
            case LTE -> Conditions.collect(() -> c.le(v));
            case CONTAINS -> Conditions.collect(() -> ExprText.of(c).contains(words));
            case NOT_CONTAINS -> orNull(c, Nodes.not(Conditions.collect(() -> ExprText.of(c).contains(words))));
            case STARTS_WITH -> Conditions.collect(() -> ExprText.of(c).startsWith(words));
            default -> throw new AssertionError(r.op());
        };
    }

    /** 不等于一类的比较把空值也算作满足：{@code (c) OR x IS NULL}，同前端。 */
    private static Cond orNull(Expr<?> column, Cond c) {
        return c.isEmpty() ? c : Nodes.any(List.of(c, Conditions.collect(column::isNull)));
    }

    /** 空：null、空白文字。 */
    private static boolean blank(Object value) { return value == null || (value instanceof String s && s.isBlank()); }

    /**
     * JSON 读出的数字、布尔写成文字再按列的类型读（Long 列、BigDecimal 列都能接）；文字原样。
     * double 经 BigDecimal 写成不带指数的文字（Jackson 默认把小数读成 double）。
     */
    private static Object scalar(Object value) {
        if (value instanceof Double || value instanceof Float) return BigDecimal.valueOf(((Number) value).doubleValue()).toPlainString();
        if (value instanceof BigDecimal d) return d.toPlainString();
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return value;
    }
}
