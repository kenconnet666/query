package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.scope.SkipEmpty;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code where(filter)} 的实现：条件对象的每个字段变成一个条件，见 {@link Where}。列在构造时就按名字找好，
 * 所以字段名写错时无论有没有值都会报错；值为 null、空白字符串或空集合的字段不产生条件。
 */
public final class FilterObject {
    /** 工具类，不能实例化。 */
    private FilterObject() { }

    /** 把 filter 的各字段变成条件，全部满足（AND）。 */
    public static Cond condition(SelectSpec spec, Object filter) {
        if (filter == null) throw new IllegalArgumentException("where(...) needs a filter object, got null");
        if (filter instanceof Cond) throw new IllegalArgumentException("where(...) takes a lambda or a filter object; write where(t -> condition)");
        Class<?> type = filter.getClass();
        List<Cond> all = new ArrayList<>();
        for (PropertyMeta p : Metadata.entity(type).properties()) {
            // record 的组件不会被元数据跳过（构造时都要用），这里单独跳过 @Ignore
            if (p.field().isAnnotationPresent(Ignore.class)) continue;
            Where where = p.field().getAnnotation(Where.class);
            String[] paths = where == null || where.value().length == 0 ? new String[] {p.name()} : where.value();
            Where.Op op = where == null ? Where.Op.EQ : where.op();
            Object value = read(p, filter);
            String owner = type.getSimpleName() + "." + p.name();
            List<Cond> any = new ArrayList<>();
            for (String path : paths) {
                if (!(Members.path(spec, path, owner) instanceof Expr<?> column)) {
                    throw new IllegalArgumentException(owner + ": " + path + " is a relation, not a column");
                }
                any.add(SkipEmpty.within(true, () -> compare(column, op, value, owner)));
            }
            all.add(Sql.any(any));
        }
        return Sql.all(all);
    }

    /** 一个字段的条件；值为空时由 skipEmpty 规则省略。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Cond compare(Expr column, Where.Op op, Object value, String owner) {
        if (value instanceof Collection<?> values) {
            return switch (op) {
                case EQ -> column.in(values);
                case NE -> column.notIn(values);
                default -> throw new IllegalArgumentException(owner + ": a collection can only be compared with EQ (IN) or NE (NOT IN)");
            };
        }
        return switch (op) {
            case EQ -> column.eq(value);
            case NE -> column.ne(value);
            case GT -> column.gt(value);
            case GE -> column.ge(value);
            case LT -> column.lt(value);
            case LE -> column.le(value);
            case CONTAINS -> Sql.contains(column, text(value));
            case STARTS_WITH -> Sql.startsWith(column, text(value));
            case ENDS_WITH -> Sql.endsWith(column, text(value));
        };
    }

    /** 文本比较的值；null 保持为 null，以便被省略。 */
    private static String text(Object value) { return value == null ? null : value.toString(); }

    /** 读取字段值。 */
    private static Object read(PropertyMeta p, Object filter) {
        try {
            p.field().trySetAccessible();
            return p.field().get(filter);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + filter.getClass().getSimpleName() + "." + p.name(), e);
        }
    }
}
