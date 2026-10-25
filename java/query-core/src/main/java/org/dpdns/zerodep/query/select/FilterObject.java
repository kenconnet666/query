package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.wire.Wire;

import java.lang.reflect.Array;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

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
        // 别的函数接口的 lambda（例如 java.util.function.Consumer 变量）会落到 where(Object)：它没有字段，不能当作没有条件
        if (type.isSynthetic() || type.isHidden() || Metadata.entity(type).properties().isEmpty()) {
            throw new IllegalArgumentException("where(...) got " + type.getName() + ", which is not a filter object with fields;"
                + " pass the condition as a lambda, e.g. where(t -> condition.accept(t)) for a Consumer");
        }
        List<Cond> all = new ArrayList<>();
        Map<String, Group> groups = new LinkedHashMap<>();
        for (PropertyMeta p : Metadata.entity(type).properties()) {
            // record 的组件不会被元数据跳过（构造时都要用），这里单独跳过 @Ignore
            if (p.field().isAnnotationPresent(Ignore.class)) continue;
            Where where = p.field().getAnnotation(Where.class);
            String[] paths = where == null || where.value().length == 0 ? new String[] {p.name()} : where.value();
            Where.Op op = where == null ? Where.Op.EQ : where.op();
            Where.Operator custom = where == null || where.using() == Where.Operator.class ? null : operator(where.using());
            Object value = read(p, filter);
            String owner = type.getSimpleName() + "." + p.name();
            List<Cond> any = new ArrayList<>();
            for (String path : paths) {
                Step step = new Step(path.split("\\."), path, op, custom, value, owner);
                Object start = Members.find(spec, step.steps[0], owner);
                if (start == null) throw new IllegalArgumentException(owner + ": the query's tables have no " + path);
                Group group = paths.length == 1 ? group(start, step, groups) : null;
                if (group == null) any.add(along(start, 1, step));
                else group.steps.add(step);
            }
            all.add(Nodes.any(any));
        }
        for (Group g : groups.values()) all.add(exists(g.many, r -> Nodes.all(g.steps.stream().map(st -> along(r, g.rest, st)).toList())));
        return Nodes.all(all);
    }

    /** 一个字段沿一条路径的比较：steps 是拆开的路径，其余是字段的比较方式（custom 不为 null 时用它）、值和报错用的名字。 */
    private record Step(String[] steps, String path, Where.Op op, Where.Operator custom, Object value, String owner) { }

    /** 自定义比较的实例，每个类只创建一次。 */
    private static final Map<Class<?>, Where.Operator> OPERATORS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 取（必要时创建）自定义比较的实例。 */
    private static Where.Operator operator(Class<? extends Where.Operator> type) {
        return OPERATORS.computeIfAbsent(type, t -> {
            try {
                var constructor = t.getDeclaredConstructor();
                constructor.trySetAccessible();
                return (Where.Operator) constructor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("@Where(using = " + t.getSimpleName() + ".class) needs a no-argument constructor", e);
            }
        });
    }

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
        if (step.custom != null) {
            return SkipEmpty.within(true, () -> SkipEmpty.skips(step.value) ? Cond.EMPTY : Conditions.collect(() -> step.custom.apply(column, step.value)));
        }
        return SkipEmpty.within(true, () -> Conditions.collect(() -> compare(column, step.op, step.value, step.owner)));
    }

    /** {@code EXISTS (目标行满足 inner)}；inner 为空条件（值都为空）时整个条件省略，而不是变成"至少有一行"。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Cond exists(Many many, Function<Object, Cond> inner) {
        Cond[] built = new Cond[1];
        Cond c = Conditions.collect(() -> many.any(r -> Conditions.add(built[0] = inner.apply(r))));
        return built[0].isEmpty() ? Cond.EMPTY : c;
    }

    /** 写下一个字段的条件；值为空时由 skipEmpty 规则省略，文字按列的类型读。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void compare(Expr column, Where.Op op, Object value, String owner) {
        if (op == Where.Op.RANGE) {
            range(column, value, owner);
            return;
        }
        if (value instanceof Collection<?> values) {
            List<Object> read = new ArrayList<>();
            for (Object v : values) if ((v = textAs(column, v)) != null) read.add(v);
            switch (op) {
                case EQ -> column.in(read);
                case NE -> column.notIn(read);
                default -> throw new IllegalArgumentException(owner + ": a collection can only be compared with EQ (IN), NE (NOT IN) or RANGE");
            }
            return;
        }
        if (op != Where.Op.CONTAINS && op != Where.Op.STARTS_WITH && op != Where.Op.ENDS_WITH) value = textAs(column, value);
        switch (op) {
            case EQ -> column.eq(value);
            case NE -> column.ne(value);
            case GT -> column.gt(value);
            case GE -> column.ge(value);
            case LT -> column.lt(value);
            case LE -> column.le(value);
            case CONTAINS -> ExprText.of(column).contains(text(value));
            case STARTS_WITH -> ExprText.of(column).startsWith(text(value));
            case ENDS_WITH -> ExprText.of(column).endsWith(text(value));
            case RANGE -> throw new AssertionError("handled above");
        }
    }

    /**
     * 区间：起端写 {@code >=}，止端写 {@code <=}。列是 LocalDateTime 而某一端是日期（LocalDate 或 'yyyy-MM-dd'）时，
     * 起端是那天的零点，止端写成 {@code <} 后一天的零点，这一整天都算在内。两端都空时不加条件。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void range(Expr column, Object value, String owner) {
        if (value == null) return;
        Object[] ends = ends(value, owner);
        boolean timestamp = column.type().javaType() == LocalDateTime.class;
        LocalDate from = timestamp ? day(ends[0]) : null;
        column.ge(from != null ? from.atStartOfDay() : textAs(column, ends[0]));
        LocalDate until = timestamp ? day(ends[1]) : null;
        if (until != null) column.lt(until.plusDays(1).atStartOfDay());
        else column.le(textAs(column, ends[1]));
    }

    /** 区间值的两端：Range、两项（或空）的集合或数组。 */
    private static Object[] ends(Object value, String owner) {
        if (value instanceof Range<?> r) return new Object[] {r.start(), r.end()};
        List<Object> items = new ArrayList<>();
        if (value instanceof Collection<?> c) items.addAll(c);
        else if (value.getClass().isArray()) for (int i = 0; i < Array.getLength(value); i++) items.add(Array.get(value, i));
        if (items.isEmpty()) return new Object[] {null, null};
        if (items.size() == 2) return items.toArray();
        throw new IllegalArgumentException(owner + ": a RANGE needs a Range or two values (start, end), got " + value);
    }

    /** 区间的一端是一天（LocalDate 或 'yyyy-MM-dd'）时返回那天，否则 null。 */
    private static LocalDate day(Object bound) {
        if (bound instanceof LocalDate d) return d;
        if (bound instanceof String s && DAY.matcher(s.trim()).matches()) return Wire.parse(LocalDate.class, s.trim());
        return null;
    }

    /** 'yyyy-MM-dd'。 */
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 文字按列的 Java 类型读（地址栏里的值都是文字）：空白是 null（不加条件），列本身是文字时原样返回；别的值原样返回。
     */
    static Object textAs(Expr<?> column, Object value) {
        if (!(value instanceof String s)) return value;
        if (s.isBlank()) return null;
        Class<?> type = column.type().javaType();
        return type == String.class || type == Object.class || type == CharSequence.class ? s : Wire.parse(type, s.trim());
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
