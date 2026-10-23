package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.MappingPlan;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Derived;
import org.dpdns.zerodep.query.table.Source;
import org.dpdns.zerodep.query.type.SqlType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一条完整、不可变的查询，每行结果是 R。由作用域的 {@code select(...)} / {@code selectAs(...)} 得到，
 * 可以用 {@link #unionAll} 合并，交给方言的数据库实例执行。
 *
 * <p>渲染结果按数据库实例缓存：同一个查询对象只渲染一次。
 */
public final class Query<R> {
    /** UNION 的一段：SELECT 的内容和它的投影列。 */
    record Arm(SelectSpec spec, List<Expr<?>> items) { }

    /**
     * 各段怎样合并：除第一段外每段前面的运算符，以及需要加括号的段范围。UNION 与 UNION ALL 从左到右结合，
     * 只有混用两者的右侧才需要自己的括号。
     */
    record Combination(List<String> operators, List<Group> groups) {
        static final Combination NONE = new Combination(List.of(), List.of());

        /** 第 first 到第 last 段放在一对括号里。 */
        record Group(int first, int last) { }

        Combination {
            operators = List.copyOf(operators);
            groups = List.copyOf(groups);
        }

        /** 本组合之后用 operator 接上 right。 */
        Combination then(String operator, Combination right) {
            int shift = operators.size() + 1;
            List<String> ops = new ArrayList<>(operators);
            ops.add(operator);
            ops.addAll(right.operators);
            List<Group> all = new ArrayList<>(groups);
            for (Group g : right.groups) all.add(new Group(g.first + shift, g.last + shift));
            if (right.operators.stream().anyMatch(op -> !op.equals(operator))) all.add(new Group(shift, shift + right.operators.size()));
            return new Combination(ops, all);
        }

        /** 第 arm 段之前要开几个括号。 */
        int opens(int arm) { return (int) groups.stream().filter(g -> g.first == arm).count(); }

        /** 第 arm 段之后要关几个括号。 */
        int closes(int arm) { return (int) groups.stream().filter(g -> g.last == arm).count(); }
    }

    /** 把一行解码后的列值变成结果对象。 */
    @FunctionalInterface
    interface RowMapper<R> {
        R map(Object[] values);
    }

    /** 最近一次渲染的缓存：key 是渲染它的数据库实例，同一实例再次渲染时直接复用。 */
    private record Memo(Object key, Rendered rendered) { }

    /** 怎样执行而不是查什么；null 表示用数据库实例的默认值。 */
    private record Execution(Duration ttl, Duration timeout) {
        static final Execution DEFAULT = new Execution(null, null);
    }

    /** 各段 SELECT；没有 UNION 时只有一段。 */
    private final List<Arm> arms;
    /** 各段之间的合并方式；单段查询为 {@link Combination#NONE}。 */
    private final Combination combination;
    /** 结果列名，按 SELECT 顺序。 */
    private final List<String> labels;
    /** 列名是否有意义（cols、into、selectAs、values）：UNION 时按列名对齐，否则按位置。 */
    private final boolean byName;
    /** 把一行解码后的列值变成结果对象。 */
    private final RowMapper<R> mapper;
    /** 结果类；只有按名映射到类的查询才有，这样的查询可以做 CTE 或派生表。 */
    private final Class<R> shape;
    /** 合并后整体的 LIMIT；单段查询的 LIMIT 在该段的 spec 里，这里为 null。 */
    private final Integer limit;
    /** 合并后整体的 OFFSET；同上。 */
    private final Integer offset;
    private final Execution execution;
    /** 渲染缓存；volatile，多线程同时渲染只是多做一次，结果相同。 */
    private volatile Memo memo;

    /** 所有字段的完整构造；各公开方法通过它派生出新的不可变查询。 */
    private Query(List<Arm> arms, Combination combination, List<String> labels, boolean byName, RowMapper<R> mapper, Class<R> shape,
                  Integer limit, Integer offset, Execution execution) {
        this.arms = List.copyOf(arms);
        this.combination = combination;
        this.labels = List.copyOf(labels);
        this.byName = byName;
        this.mapper = mapper;
        this.shape = shape;
        this.limit = limit;
        this.offset = offset;
        this.execution = execution;
    }

    // ---------- 构造 ----------

    /** 作用域的 select(...)：单个表达式、cols(...) 或 row(...)。 */
    @SuppressWarnings("unchecked")
    public static <R> Query<R> select(SelectSpec spec, Selection<R> selection) {
        Objects.requireNonNull(selection, "select(...) returned null");
        if (selection instanceof Expr<?> e) {
            String label = e.label() == null ? "value" : e.label();
            return new Query<>(List.of(new Arm(spec, List.of(e))), Combination.NONE, List.of(label), false, values -> (R) values[0],
                null, null, null, Execution.DEFAULT);
        }
        if (selection instanceof Cols c) {
            List<String> names = c.names();
            RowMapper<R> toMap = values -> {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) row.put(names.get(i), values[i]);
                return (R) row;
            };
            return new Query<>(List.of(new Arm(spec, c.items())), Combination.NONE, names, true, toMap, null, null, null, Execution.DEFAULT);
        }
        List<Expr<?>> items = new ArrayList<>();
        RowSelection.collect(selection, items);
        RowMapper<R> build = values -> (R) RowSelection.read(selection, values, new int[1]);
        return new Query<>(List.of(new Arm(spec, items)), Combination.NONE, labelsOf(items), false, build, null, null, null, Execution.DEFAULT);
    }

    /** selectAs(VO.class)：VO 的每个属性取自作用域里恰好一张表的同名列。 */
    public static <V> Query<V> auto(SelectSpec spec, Class<V> type) {
        List<Expr<?>> items = new ArrayList<>();
        for (PropertyMeta p : Metadata.entity(type).properties()) items.add(sameNamed(spec, type, p.name()));
        return named(spec, type, items);
    }

    /** 常量行：每行一条字面量 SELECT，用 UNION ALL 连接。 */
    public static <V> Query<V> constants(Class<V> shape, List<? extends V> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("values() needs at least one row");
        EntityMeta<V> meta = Metadata.entity(shape);
        Query<V> all = null;
        for (V row : rows) {
            List<Expr<?>> items = new ArrayList<>();
            for (PropertyMeta p : meta.properties()) items.add(constant(shape, p, row));
            Query<V> arm = named(SelectSpec.none(), shape, items);
            all = all == null ? arm : all.unionAll(arm);
        }
        return all;
    }

    /** 列已按 type 的属性顺序排好的按名查询。 */
    private static <V> Query<V> named(SelectSpec spec, Class<V> type, List<Expr<?>> items) {
        List<String> labels = Metadata.entity(type).properties().stream().map(PropertyMeta::name).toList();
        MappingPlan<V> plan = MappingPlan.of(type, labels);
        return new Query<>(List.of(new Arm(spec, items)), Combination.NONE, labels, true, plan::map, type, null, null, Execution.DEFAULT);
    }

    /** 把 row 的一个属性值变成字面量，values() 用它拼出每行的 SELECT。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Expr<?> constant(Class<?> shape, PropertyMeta p, Object row) {
        Object value;
        try {
            p.field().trySetAccessible();
            value = p.field().get(row);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + shape.getSimpleName() + "." + p.name(), e);
        }
        if (value == null) {
            throw new IllegalArgumentException(shape.getSimpleName() + "." + p.name() + " is null; every column of values() rows needs a value");
        }
        return new Nodes.Literal(p.type(), value);
    }

    /** 在作用域的表里找与属性同名的那一列；没有或多于一张表有同名列时报错，提示改用 cols(...)。 */
    private static Expr<?> sameNamed(SelectSpec spec, Class<?> type, String name) {
        List<Expr<?>> hits = new ArrayList<>();
        for (SelectSpec.Join j : spec.joins) {
            EntityMeta<?> meta = Metadata.entity(j.ref().entity());
            if (meta.has(name)) hits.add(new Col<>(j.ref(), meta.property(name)));
        }
        if (hits.size() == 1) return hits.get(0);
        throw new IllegalArgumentException(type.getSimpleName() + "." + name + (hits.isEmpty()
            ? " has no same-named column in the query's tables; select it with cols(...).into(" + type.getSimpleName() + ".class)"
            : " matches columns of " + hits.size() + " tables; select it with cols(...).into(" + type.getSimpleName() + ".class)"));
    }

    /** row(...) 与单列查询的列名：表达式自己的名字，没有时为 c1、c2…，重名时加 _2。 */
    private static List<String> labelsOf(List<Expr<?>> items) {
        List<String> labels = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String label = items.get(i).label();
            if (label == null) label = "c" + (i + 1);
            String unique = label;
            for (int n = 2; !used.add(unique.toLowerCase(Locale.ROOT)); n++) unique = label + "_" + n;
            labels.add(unique);
        }
        return labels;
    }

    // ---------- 合并 ----------

    /** UNION ALL。各段的结果类型必须相同（javac 检查）；按名的查询按列名对齐，其余按位置。 */
    public Query<R> unionAll(Query<R> other) { return combine("UNION ALL", other); }

    /** UNION（去重），规则同 {@link #unionAll}。 */
    public Query<R> union(Query<R> other) { return combine("UNION", other); }

    /** 合并两个查询：校验列数、LIMIT 和 ORDER BY 的位置，按名的查询把右侧列按列名对齐到左侧。 */
    private Query<R> combine(String operator, Query<R> other) {
        if (other.columnCount() != columnCount()) {
            throw new IllegalArgumentException("UNION arms select " + columnCount() + " and " + other.columnCount() + " columns");
        }
        if (limit != null || offset != null || other.limit != null || other.offset != null) {
            throw new IllegalStateException("Apply limit/offset after combining queries, not before");
        }
        for (Arm a : other.arms) {
            if (a.spec().hasOrderOrLimit()) throw new IllegalStateException("A UNION arm cannot have ORDER BY or LIMIT; wrap it in a CTE");
        }
        if (arms.size() == 1 && arms.get(0).spec().hasOrderOrLimit()) {
            throw new IllegalStateException("A UNION arm cannot have ORDER BY or LIMIT; wrap it in a CTE");
        }
        List<Arm> right = byName && other.byName ? other.reordered(positions(other.labels, labels, "UNION arms")) : other.arms;
        List<Arm> allArms = new ArrayList<>(arms);
        allArms.addAll(right);
        return new Query<>(allArms, combination.then(operator, other.combination), labels, byName, mapper, shape, null, null, execution);
    }

    // ---------- 结果形态 ----------

    /**
     * 同一条 SQL，按列名映射到另一个类：record、Lombok {@code @Data}（含链式 setter）、{@code @Builder} 或标量。
     * 列名忽略大小写和下划线；类缺少的列被忽略，类的属性没有对应列时报错。按名的查询会把列重排成类的属性顺序，
     * 于是结果可以做 CTE 或派生表。
     */
    public <V> Query<V> into(Class<V> type) {
        if (!byName || MappingPlan.isScalar(type)) {
            MappingPlan<V> plan = MappingPlan.of(type, labels);
            return new Query<>(arms, combination, labels, byName, plan::map, null, limit, offset, execution);
        }
        List<String> properties = Metadata.entity(type).properties().stream().map(PropertyMeta::name).toList();
        int[] order = propertyOrder(properties);
        List<String> ordered = new ArrayList<>();
        for (int i : order) {
            // 列名改成属性的写法，CTE 和派生表按属性名引用列
            String label = labels.get(i);
            ordered.add(properties.stream().filter(p -> normalize(p).equals(normalize(label))).findFirst().orElse(label));
        }
        MappingPlan<V> plan = MappingPlan.of(type, ordered);
        return new Query<>(reordered(order), combination, ordered, true, plan::map, type, limit, offset, execution);
    }

    /** 同一条 SQL，在映射好的每行上再套一个 Java 函数。 */
    public <V> Query<V> map(Function<? super R, ? extends V> function) {
        Objects.requireNonNull(function, "function");
        RowMapper<R> inner = mapper;
        return new Query<>(arms, combination, labels, byName, values -> function.apply(inner.map(values)), null, limit, offset, execution);
    }

    /** 结果在数据库实例的缓存里保存 ttl，按 SQL 和参数作键。 */
    public Query<R> cached(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        return new Query<>(arms, combination, labels, byName, mapper, shape, limit, offset, new Execution(ttl, execution.timeout()));
    }

    /** 语句运行超过 timeout 时由数据库取消；覆盖数据库实例的 queryTimeout。 */
    public Query<R> timeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        return new Query<>(arms, combination, labels, byName, mapper, shape, limit, offset, new Execution(execution.ttl(), timeout));
    }

    /** 最多返回 rows 行；合并后的查询作用于整体。 */
    public Query<R> limit(int rows) {
        if (arms.size() == 1) return withSpec(arms.get(0).spec().limit(rows));
        if (rows < 0) throw new IllegalArgumentException("limit must not be negative");
        return new Query<>(arms, combination, labels, byName, mapper, shape, rows, offset, execution);
    }

    /** 跳过前 rows 行；合并后的查询作用于整体。 */
    public Query<R> offset(int rows) {
        if (arms.size() == 1) return withSpec(arms.get(0).spec().offset(rows));
        if (rows < 0) throw new IllegalArgumentException("offset must not be negative");
        return new Query<>(arms, combination, labels, byName, mapper, shape, limit, rows, execution);
    }

    /** 把 LIMIT 降到最多 rows，已有更小的 LIMIT 时保持不变。 */
    public Query<R> limitAtMost(int rows) {
        Integer current = arms.size() == 1 ? arms.get(0).spec().limit : limit;
        return current != null && current <= rows ? this : limit(rows);
    }

    /** 单段查询换一个 SELECT 规格，其余不变。 */
    private Query<R> withSpec(SelectSpec spec) {
        return new Query<>(List.of(new Arm(spec, arms.get(0).items())), combination, labels, byName, mapper, shape, limit, offset, execution);
    }

    /** 作为派生表使用：{@code from(query.asTable())}，列用 {@code t.col(Row::x)} 读取。需要先 into(X.class)。 */
    public Source<R> asTable() {
        requireShape("a derived table");
        return new Derived<>(this);
    }

    // ---------- 查看 ----------

    /** 结果的列名，按 SELECT 的顺序。 */
    public List<String> labels() { return labels; }

    /** 只按方言渲染，不带任何数据库实例的过滤器和改写，便于测试。 */
    public Rendered toSql(Dialect dialect) { return QueryRenderer.statement(new SqlWriter(dialect), this); }

    /** 只显示列名，便于调试。 */
    @Override public String toString() { return "Query" + labels; }

    // ---------- 包内使用 ----------

    /** 各段 SELECT。 */
    List<Arm> arms() { return arms; }

    /** 各段的合并方式。 */
    Combination combination() { return combination; }

    /** 合并后整体的 LIMIT，没有时为 null。 */
    Integer limitValue() { return limit; }

    /** 合并后整体的 OFFSET，没有时为 null。 */
    Integer offsetValue() { return offset; }

    /** 结果缓存时长，null 表示不缓存。 */
    public Duration ttl() { return execution.ttl(); }

    /** 语句超时，null 表示用数据库实例的默认值。 */
    public Duration timeout() { return execution.timeout(); }

    /** CTE 或派生表的行类。 */
    public Class<R> shape() {
        requireShape("a CTE or derived table");
        return shape;
    }

    /** 要求查询已映射到类，否则不能做 CTE 或派生表。 */
    private void requireShape(String use) {
        if (shape == null) {
            throw new IllegalStateException("Only a query mapped into a class can be used as " + use
                + ": add .into(Row.class), or use selectAs(Row.class) or values(...)");
        }
    }

    /** 列数；各段列数相同，取第一段。 */
    int columnCount() { return arms.get(0).items().size(); }

    /** 各列的类型，执行时按它读取。 */
    public List<SqlType<?>> columnTypes() {
        List<SqlType<?>> types = new ArrayList<>();
        for (Expr<?> e : arms.get(0).items()) types.add(e.type());
        return types;
    }

    /** 标量子查询或 IN 子查询的那一列的类型。 */
    @SuppressWarnings("unchecked")
    public SqlType<R> singleColumnType() {
        if (columnCount() != 1) throw new IllegalStateException("A scalar or IN subquery must select exactly one column, this one selects " + columnCount());
        return (SqlType<R>) arms.get(0).items().get(0).type();
    }

    /** 把一行解码后的列值映射成结果对象。 */
    public R mapRow(Object[] values) { return mapper.map(values); }

    /** 按数据库实例缓存的渲染结果。 */
    public Rendered rendered(Object key, Supplier<Rendered> render) {
        Memo m = memo;
        if (m != null && m.key() == key) return m.rendered();
        Rendered r = render.get();
        memo = new Memo(key, r);
        return r;
    }

    /** 映射到类时的列顺序：先是与属性同名的列（属性顺序），再是其余的列。 */
    private int[] propertyOrder(List<String> properties) {
        List<Integer> order = new ArrayList<>();
        for (String p : properties) {
            for (int i = 0; i < labels.size(); i++) {
                if (normalize(labels.get(i)).equals(normalize(p)) && !order.contains(i)) {
                    order.add(i);
                    break;
                }
            }
        }
        for (int i = 0; i < labels.size(); i++) if (!order.contains(i)) order.add(i);
        return order.stream().mapToInt(Integer::intValue).toArray();
    }

    /** from 的每个列名在 to 里的位置；两边列名必须一一对应。 */
    private static int[] positions(List<String> from, List<String> to, String what) {
        int[] order = new int[to.size()];
        for (int i = 0; i < to.size(); i++) {
            order[i] = -1;
            for (int j = 0; j < from.size(); j++) if (normalize(from.get(j)).equals(normalize(to.get(i)))) order[i] = j;
            if (order[i] < 0) throw new IllegalArgumentException(what + " have different columns: " + to + " and " + from);
        }
        return order;
    }

    /** 各段的列按 order 重新排列。 */
    private List<Arm> reordered(int[] order) {
        List<Arm> out = new ArrayList<>();
        for (Arm a : arms) {
            List<Expr<?>> items = new ArrayList<>();
            for (int i : order) items.add(a.items().get(i));
            out.add(new Arm(a.spec(), items));
        }
        return out;
    }

    /** 列名与属性名的比较形式：去掉下划线并忽略大小写。 */
    private static String normalize(String name) { return name.replace("_", "").toLowerCase(Locale.ROOT); }
}
