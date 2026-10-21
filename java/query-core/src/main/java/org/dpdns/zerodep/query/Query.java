package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.MappingPlan;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.type.SqlType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A complete, immutable query producing rows of type R. Built by the {@code select...} methods of
 * a scope, combined with {@link #unionAll}, and executed by a dialect's database instance.
 *
 * <p>Rendering is memoized per database instance: the same query object renders once.
 */
public final class Query<R> {
    record Arm(SelectSpec spec, List<Expr<?>> items) { }

    /**
     * How the arms combine: the operator before every arm but the first, and the arm ranges kept in
     * parentheses. UNION and UNION ALL bind left to right, so only a right-hand side that mixes them
     * needs parentheses of its own.
     */
    record Combination(List<String> operators, List<Group> groups) {
        static final Combination NONE = new Combination(List.of(), List.of());

        record Group(int first, int last) { }

        Combination {
            operators = List.copyOf(operators);
            groups = List.copyOf(groups);
        }

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

        int opens(int arm) { return (int) groups.stream().filter(g -> g.first == arm).count(); }

        int closes(int arm) { return (int) groups.stream().filter(g -> g.last == arm).count(); }
    }

    @FunctionalInterface
    interface RowMapper<R> {
        R map(Object[] values);
    }

    private record Memo(Object key, Rendered rendered) { }

    private final List<Arm> arms;
    private final Combination combination;
    private final List<String> labels;
    private final RowMapper<R> mapper;
    private final Class<R> shape;
    private final Integer limit;
    private final Integer offset;
    private final Duration ttl;
    private volatile Memo memo;

    private Query(List<Arm> arms, Combination combination, List<String> labels, RowMapper<R> mapper, Class<R> shape,
                  Integer limit, Integer offset, Duration ttl) {
        this.arms = List.copyOf(arms);
        this.combination = combination;
        this.labels = List.copyOf(labels);
        this.mapper = mapper;
        this.shape = shape;
        this.limit = limit;
        this.offset = offset;
        this.ttl = ttl;
    }

    // ---------- construction ----------

    static <R> Query<R> functional(SelectSpec spec, Function<Row, R> projection) {
        List<Expr<?>> items = new ArrayList<>();
        projection.apply(Row.recording(items));
        if (items.isEmpty()) throw new IllegalArgumentException("The projection reads no column; read values with row.get(...)");
        List<Expr<?>> fixed = List.copyOf(items);
        RowMapper<R> mapper = values -> {
            Row row = Row.replay(fixed, values);
            R result = projection.apply(row);
            row.finish();
            return result;
        };
        return new Query<>(List.of(new Arm(spec, fixed)), Combination.NONE, labelsOf(fixed), mapper, null, null, null, null);
    }

    /** Every property of the shape is taken from the same-named property of exactly one source, or from its @MapFrom. */
    static <V> Query<V> auto(SelectSpec spec, Class<V> shape) {
        List<Expr<?>> items = new ArrayList<>();
        for (PropertyMeta target : Metadata.entity(shape).properties()) items.add(autoColumn(spec, shape, target).as(target.name()));
        return named(spec, shape, items);
    }

    /** Labels must be exactly the shape's properties, so the result can also serve as a CTE or derived table. */
    static <V> Query<V> named(SelectSpec spec, Class<V> shape, List<? extends Expr<?>> items) {
        EntityMeta<V> meta = Metadata.entity(shape);
        List<String> labels = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Expr<?> item : items) {
            String label = item.label();
            if (label == null) {
                throw new IllegalArgumentException("Every column of selectAs(" + shape.getSimpleName() + ", ...) needs a name; add .as("
                    + shape.getSimpleName() + "::getX)");
            }
            if (!meta.has(label)) throw new IllegalArgumentException(shape.getSimpleName() + " has no property '" + label + "'");
            if (!seen.add(label)) throw new IllegalArgumentException("Column '" + label + "' is selected twice");
            labels.add(label);
        }
        Set<String> missing = new LinkedHashSet<>();
        for (PropertyMeta p : meta.properties()) if (!seen.contains(p.name())) missing.add(p.name());
        if (!missing.isEmpty()) throw new IllegalArgumentException(shape.getSimpleName() + " properties without a column: " + missing);
        MappingPlan<V> plan = MappingPlan.of(shape, labels);
        return new Query<>(List.of(new Arm(spec, List.copyOf(items))), Combination.NONE, labels, plan::map, shape, null, null, null);
    }

    @SuppressWarnings("unchecked")
    static <T> Query<T> value(SelectSpec spec, Expr<T> item) {
        String label = item.label() == null ? "value" : item.label();
        return new Query<>(List.of(new Arm(spec, List.of(item))), Combination.NONE, List.of(label), values -> (T) values[0], null, null, null, null);
    }

    /** One SELECT of literals per row, combined with UNION ALL. */
    static <V> Query<V> constants(Class<V> shape, List<? extends V> rows) {
        if (rows == null || rows.isEmpty()) throw new IllegalArgumentException("values() needs at least one row");
        EntityMeta<V> meta = Metadata.entity(shape);
        Query<V> all = null;
        for (V row : rows) {
            List<Expr<?>> items = new ArrayList<>();
            for (PropertyMeta p : meta.properties()) items.add(constant(shape, p, row).as(p.name()));
            Query<V> arm = named(SelectSpec.none(), shape, items);
            all = all == null ? arm : all.unionAll(arm);
        }
        return all;
    }

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

    private static Expr<?> autoColumn(SelectSpec spec, Class<?> shape, PropertyMeta target) {
        List<Ref<?>> refs = spec.joins.stream().<Ref<?>>map(SelectSpec.Join::ref).toList();
        if (target.mapFrom() != null) {
            Class<?> entity = target.mapFrom().value();
            List<Ref<?>> hits = refs.stream().filter(r -> r.entity() == entity).toList();
            if (hits.size() != 1) {
                throw new IllegalArgumentException(shape.getSimpleName() + "." + target.name() + ": @MapFrom(" + entity.getSimpleName()
                    + ") needs exactly one " + entity.getSimpleName() + " in the query, found " + hits.size());
            }
            Ref<?> ref = hits.get(0);
            return new Nodes.Column<>(ref, Metadata.entity(ref.entity()).property(target.mapFrom().property()));
        }
        List<Expr<?>> hits = new ArrayList<>();
        for (Ref<?> r : refs) {
            EntityMeta<?> meta = Metadata.entity(r.entity());
            if (meta.has(target.name())) hits.add(new Nodes.Column<>(r, meta.property(target.name())));
        }
        if (hits.size() == 1) return hits.get(0);
        throw new IllegalArgumentException(shape.getSimpleName() + "." + target.name() + (hits.isEmpty()
            ? " has no same-named property in the joined sources; use selectAs(" + shape.getSimpleName() + ", ...) or @MapFrom"
            : " matches " + hits.size() + " sources; pick one with @MapFrom or selectAs(" + shape.getSimpleName() + ", ...)"));
    }

    private static List<String> labelsOf(List<Expr<?>> items) {
        List<String> labels = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String label = items.get(i).label();
            if (label == null) label = "c" + (i + 1);
            String unique = label;
            for (int n = 2; !used.add(unique.toLowerCase()); n++) unique = label + "_" + n;
            labels.add(unique);
        }
        return labels;
    }

    // ---------- combination ----------

    /** Arms must produce the same row type, so javac rejects a UNION of different shapes. */
    public Query<R> unionAll(Query<R> other) { return combine("UNION ALL", other); }

    public Query<R> union(Query<R> other) { return combine("UNION", other); }

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
        List<Arm> allArms = new ArrayList<>(arms);
        allArms.addAll(other.arms);
        return new Query<>(allArms, combination.then(operator, other.combination), labels, mapper, shape, null, null, ttl);
    }

    // ---------- result shaping ----------

    /** Same SQL, mapped by column label into another class (record, bean, builder or scalar). */
    public <V> Query<V> mapTo(Class<V> type) {
        MappingPlan<V> plan = MappingPlan.of(type, labels);
        return new Query<>(arms, combination, labels, plan::map, null, limit, offset, ttl);
    }

    /** Same SQL, with a Java function applied to every mapped row. */
    public <V> Query<V> map(Function<? super R, ? extends V> function) {
        Objects.requireNonNull(function, "function");
        RowMapper<R> inner = mapper;
        return new Query<>(arms, combination, labels, values -> function.apply(inner.map(values)), null, limit, offset, ttl);
    }

    /** Results are kept in the database instance's cache for ttl, keyed by SQL and parameters. */
    public Query<R> cached(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        return new Query<>(arms, combination, labels, mapper, shape, limit, offset, ttl);
    }

    public Query<R> limit(int rows) {
        if (arms.size() == 1) return withSpec(arms.get(0).spec().limit(rows));
        if (rows < 0) throw new IllegalArgumentException("limit must not be negative");
        return new Query<>(arms, combination, labels, mapper, shape, rows, offset, ttl);
    }

    public Query<R> offset(int rows) {
        if (arms.size() == 1) return withSpec(arms.get(0).spec().offset(rows));
        if (rows < 0) throw new IllegalArgumentException("offset must not be negative");
        return new Query<>(arms, combination, labels, mapper, shape, limit, rows, ttl);
    }

    /** Lowers the limit to at most {@code rows}, keeping a smaller limit the query already has. */
    Query<R> limitAtMost(int rows) {
        Integer current = arms.size() == 1 ? arms.get(0).spec().limit : limit;
        return current != null && current <= rows ? this : limit(rows);
    }

    private Query<R> withSpec(SelectSpec spec) {
        return new Query<>(List.of(new Arm(spec, arms.get(0).items())), combination, labels, mapper, shape, limit, offset, ttl);
    }

    /** As a derived table: {@code from(query.asTable())}. Needs a named projection. */
    public Source<R> asTable() {
        requireShape("a derived table");
        return new Derived<>(this);
    }

    // ---------- inspection ----------

    public List<String> labels() { return labels; }

    /** Renders for one dialect, without any database instance's filters or overrides. Handy in tests. */
    public Rendered toSql(Dialect dialect) { return QueryRenderer.statement(new SqlWriter(dialect), this); }

    @Override public String toString() { return "Query" + labels; }

    // ---------- package internals ----------

    List<Arm> arms() { return arms; }

    Combination combination() { return combination; }

    Integer limitValue() { return limit; }

    Integer offsetValue() { return offset; }

    Duration ttl() { return ttl; }

    Class<R> shape() {
        requireShape("a CTE or derived table");
        return shape;
    }

    private void requireShape(String use) {
        if (shape == null) {
            throw new IllegalStateException("Only a selectAs(Shape.class, ...) query can be used as " + use + "; its columns need names");
        }
    }

    int columnCount() { return arms.get(0).items().size(); }

    List<SqlType<?>> columnTypes() {
        List<SqlType<?>> types = new ArrayList<>();
        for (Expr<?> e : arms.get(0).items()) types.add(e.type());
        return types;
    }

    @SuppressWarnings("unchecked")
    SqlType<R> singleColumnType() {
        if (columnCount() != 1) throw new IllegalStateException("A scalar or IN subquery must select exactly one column, this one selects " + columnCount());
        return (SqlType<R>) arms.get(0).items().get(0).type();
    }

    R mapRow(Object[] values) { return mapper.map(values); }

    Rendered rendered(Object key, Supplier<Rendered> render) {
        Memo m = memo;
        if (m != null && m.key() == key) return m.rendered();
        Rendered r = render.get();
        memo = new Memo(key, r);
        return r;
    }
}
