package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code where(filter)} 的实现：条件对象的每个字段变成一个条件，见 {@link Where}。列在构造时就按名字找好，
 * 所以字段名写错时无论有没有值都会报错；值为 null、空白字符串或空集合的字段不产生条件。
 * 路径经过一对多关系时写成 EXISTS；只写一条路径的字段按第一个一对多关系合并进同一个 EXISTS，
 * 即要求同一个子行满足这些字段，例如 {@code @Where("deliveries.unit")} 与 {@code @Where(value = "deliveries.quantity", op = GE)}。
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
        Map<String, Group> groups = new LinkedHashMap<>();
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
                Step step = new Step(path.split("\\."), path, op, value, owner);
                Object start = Members.find(spec, step.steps[0], owner);
                if (start == null) throw new IllegalArgumentException(owner + ": the query's tables have no " + path);
                Group group = paths.length == 1 ? group(start, step, groups) : null;
                if (group == null) any.add(along(start, 1, step));
                else group.steps.add(step);
            }
            all.add(Sql.any(any));
        }
        for (Group g : groups.values()) all.add(exists(g.many, r -> Sql.all(g.steps.stream().map(st -> along(r, g.rest, st)).toList())));
        return Sql.all(all);
    }

    /** 一个字段沿一条路径的比较：steps 是拆开的路径，其余是字段的比较方式、值和报错用的名字。 */
    private record Step(String[] steps, String path, Where.Op op, Object value, String owner) { }

    /** 走过同一个一对多关系的字段，合并进一个 EXISTS；rest 是关系之后的第一段。 */
    private record Group(Many<?> many, int rest, List<Step> steps) { }

    /** 路径在到达列之前经过一对多关系时，返回（必要时新建）这个关系的分组；不经过时为 null。 */
    private static Group group(Object start, Step step, Map<String, Group> groups) {
        Object at = start;
        for (int i = 1; i < step.steps.length; i++) {
            if (at instanceof Many<?> many) {
                int rest = i;
                return groups.computeIfAbsent(String.join(".", Arrays.copyOf(step.steps, i)), k -> new Group(many, rest, new ArrayList<>()));
            }
            at = at instanceof Entity e ? Members.invoke(e, step.steps[i]) : null;
            if (at == null) return null;
        }
        return null;
    }

    /** 从 at 出发沿 step 的第 i 段往下找列并比较；遇到一对多关系写成 EXISTS，找不到或停在关系上时报错。 */
    private static Cond along(Object at, int i, Step step) {
        for (; i < step.steps.length; i++) {
            if (at instanceof Many<?> many) {
                int rest = i;
                return exists(many, r -> along(r, rest, step));
            }
            at = at instanceof Entity e ? Members.invoke(e, step.steps[i]) : null;
            if (at == null) throw new IllegalArgumentException(step.owner + ": the query's tables have no " + step.path);
        }
        if (!(at instanceof Expr<?> column)) throw new IllegalArgumentException(step.owner + ": " + step.path + " is a relation, not a column");
        return SkipEmpty.within(true, () -> compare(column, step.op, step.value, step.owner));
    }

    /** {@code EXISTS (目标行满足 inner)}；inner 为空条件（值都为空）时整个条件省略，而不是变成"至少有一行"。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Cond exists(Many many, Function<Object, Cond> inner) {
        Cond[] built = new Cond[1];
        Cond c = many.any(r -> built[0] = inner.apply(r));
        return built[0].isEmpty() ? Cond.EMPTY : c;
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
