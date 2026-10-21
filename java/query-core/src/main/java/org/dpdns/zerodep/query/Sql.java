package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Core entry points and the functions whose semantics are the same on every supported database.
 * Dialect facades ({@code Hana}, {@code Postgres}) extend this class, so one static import of the
 * chosen facade brings both core and dialect functions.
 *
 * <p>Generic functions here take one generic operand; functions whose operands must share a type
 * live on {@link Expr} as instance methods (see its documentation for why).
 */
public class Sql {
    /** {@code a || b || ...}; dialects without the operator register their own rendering. */
    public static final FunctionDef CONCAT = FunctionDef.of("CONCAT", (w, args) -> w.sql("(").join(args, " || ").sql(")"));

    /** {@code LOCATE(text, search)}; PostgreSQL and MySQL register their own spelling. */
    public static final FunctionDef LOCATE = FunctionDef.of("LOCATE");

    protected Sql() { }

    // ---------- sources ----------

    public static <A> From1<A> from(Class<A> entity) { return From1.start(AbstractFrom.ref(entity)); }

    public static <A> From1<A> from(Source<A> source) { return From1.start(AbstractFrom.ref(source)); }

    /** A named CTE over a {@code selectAs(Shape.class, ...)} query. */
    public static <E> Cte<E> cte(String name, Query<E> body) { return new Cte<>(name, body); }

    /**
     * Constant rows such as a code list: one SELECT of literals per row, joined by UNION ALL (with
     * {@code FROM DUMMY} on HANA). Use it as a CTE or a derived table. Every property needs a value.
     */
    public static <V> Query<V> values(Class<V> shape, List<? extends V> rows) { return Query.constants(shape, rows); }

    // ---------- values ----------

    /** A bound parameter typed by the value's class. */
    @SuppressWarnings("unchecked")
    public static <T> Expr<T> val(T value) {
        Objects.requireNonNull(value, "value; use nullOf(Type.class) for NULL");
        return new Nodes.Param<>((SqlType<T>) SqlType.of(value.getClass()), value);
    }

    public static <T> Expr<T> val(T value, SqlType<T> type) { return new Nodes.Param<>(type, Objects.requireNonNull(value, "value")); }

    /** A literal written into the SQL text; use for fixed codes, never for user input. */
    @SuppressWarnings("unchecked")
    public static <T> Expr<T> lit(T value) {
        Objects.requireNonNull(value, "value; use nullOf(Type.class) for NULL");
        return new Nodes.Literal<>((SqlType<T>) SqlType.of(value.getClass()), value);
    }

    public static <T> Expr<T> nullOf(Class<T> type) { return new Nodes.Raw<>(SqlType.of(type), "NULL"); }

    // ---------- conditions ----------

    public static Cond all(Cond... conds) { return Nodes.all(Arrays.asList(conds)); }

    public static Cond all(List<Cond> conds) { return Nodes.all(conds); }

    public static Cond any(Cond... conds) { return Nodes.any(Arrays.asList(conds)); }

    public static Cond any(List<Cond> conds) { return Nodes.any(conds); }

    public static Cond not(Cond cond) { return Nodes.not(cond); }

    /** The condition only when test holds; otherwise no condition. */
    public static Cond onlyIf(boolean test, Supplier<Cond> cond) { return test ? cond.get() : Cond.EMPTY; }

    /** The condition built from value only when value is not null. */
    public static <T> Cond ifPresent(T value, Function<? super T, Cond> cond) { return value == null ? Cond.EMPTY : cond.apply(value); }

    public static Cond exists(Query<?> subquery) { return Nodes.exists(subquery, false); }

    /** EXISTS over a scope, without a SELECT list: {@code exists(from(Vbkd.class).where(v -> ...))}. */
    public static Cond exists(AbstractFrom<?> scope) { return exists(scope.selectOne()); }

    public static Cond notExists(Query<?> subquery) { return Nodes.exists(subquery, true); }

    public static Cond notExists(AbstractFrom<?> scope) { return notExists(scope.selectOne()); }

    public static Cond like(Expr<String> e, String pattern) { return Nodes.compare(e, "LIKE", e.param(pattern)); }

    public static Cond notLike(Expr<String> e, String pattern) { return Nodes.compare(e, "NOT LIKE", e.param(pattern)); }

    public static Cond likeIfPresent(Expr<String> e, String pattern) { return pattern == null ? Cond.EMPTY : like(e, pattern); }

    /** A typed escape hatch: {0}, {1} are the rendered arguments. */
    public static Cond sqlCond(String template, Expr<?>... args) { return Nodes.template(template, List.of(args)); }

    // ---------- text ----------

    public static Expr<String> upper(Expr<String> e) { return Nodes.call(e.type(), "UPPER", List.of(e)); }

    public static Expr<String> lower(Expr<String> e) { return Nodes.call(e.type(), "LOWER", List.of(e)); }

    public static Expr<String> trim(Expr<String> e) { return Nodes.call(e.type(), "TRIM", List.of(e)); }

    /** SUBSTRING(e, start, length), counting from 1; the positions are literals, so the call can be a GROUP BY key. */
    public static Expr<String> substring(Expr<String> e, int start, int length) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, lit(position(start)), lit(count(length))));
    }

    /** SUBSTRING(e, start): from start, counting from 1, to the end. */
    public static Expr<String> substring(Expr<String> e, int start) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, lit(position(start))));
    }

    /** SUBSTRING(e, start) with a computed start, e.g. one past a {@link #locate}. */
    public static Expr<String> substring(Expr<String> e, Expr<Integer> start) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, start));
    }

    /** SUBSTRING(e, start, length) with computed positions; write constants with {@code lit(1)}. */
    public static Expr<String> substring(Expr<String> e, Expr<Integer> start, Expr<Integer> length) {
        return Nodes.call(SqlType.of(String.class), "SUBSTRING", List.of(e, start, length));
    }

    public static Expr<String> left(Expr<String> e, int length) {
        return Nodes.call(SqlType.of(String.class), "LEFT", List.of(e, lit(count(length))));
    }

    public static Expr<String> right(Expr<String> e, int length) {
        return Nodes.call(SqlType.of(String.class), "RIGHT", List.of(e, lit(count(length))));
    }

    public static Expr<String> left(Expr<String> e, Expr<Integer> length) {
        return Nodes.call(SqlType.of(String.class), "LEFT", List.of(e, length));
    }

    public static Expr<String> right(Expr<String> e, Expr<Integer> length) {
        return Nodes.call(SqlType.of(String.class), "RIGHT", List.of(e, length));
    }

    /** Where search first occurs in text, counting from 1, or 0 when it does not. */
    public static Expr<Integer> locate(Expr<String> text, String search) {
        return locate(text, text.param(Objects.requireNonNull(search, "search")));
    }

    public static Expr<Integer> locate(Expr<String> text, Expr<String> search) {
        return Nodes.call(SqlType.of(Integer.class), LOCATE, List.of(text, search));
    }

    public static Expr<String> replace(Expr<String> e, String search, String replacement) {
        SqlType<String> text = SqlType.of(String.class);
        return Nodes.call(text, "REPLACE", List.of(e, new Nodes.Param<>(text, Objects.requireNonNull(search, "search")),
            new Nodes.Param<>(text, Objects.requireNonNull(replacement, "replacement"))));
    }

    private static int position(int start) {
        if (start < 1) throw new IllegalArgumentException("Text positions start at 1; negative positions differ between databases");
        return start;
    }

    private static int count(int length) {
        if (length < 0) throw new IllegalArgumentException("A length must not be negative");
        return length;
    }

    @SafeVarargs
    public static Expr<String> concat(Expr<String> first, Expr<String>... rest) {
        List<Expr<String>> args = new ArrayList<>();
        args.add(first);
        args.addAll(Arrays.asList(rest));
        return Nodes.call(SqlType.of(String.class), CONCAT, args);
    }

    // ---------- numbers ----------

    /** The result has the first operand's type. */
    @SafeVarargs
    public static <N extends Number> Expr<N> plus(Expr<N> first, Expr<? extends Number>... rest) { return arithmetic("+", first, rest); }

    /** The sum of all terms, e.g. sixteen period columns built from a list of getters. */
    public static <N extends Number> Expr<N> plus(List<? extends Expr<N>> terms) {
        if (terms.isEmpty()) throw new IllegalArgumentException("plus() needs at least one term");
        return new Nodes.Arithmetic<>(terms.get(0).type(), "+", List.copyOf(terms));
    }

    /** Unary minus: {@code -(e)}. */
    public static <N extends Number> Expr<N> negate(Expr<N> e) { return new Nodes.Negated<>(e); }

    @SafeVarargs
    public static <N extends Number> Expr<N> times(Expr<N> first, Expr<? extends Number>... rest) { return arithmetic("*", first, rest); }

    public static <N extends Number> Expr<N> minus(Expr<N> left, Expr<? extends Number> right) {
        return new Nodes.Arithmetic<>(left.type(), "-", List.of(left, right));
    }

    /** Decimal division only: integer division truncates on some databases and not on others. */
    public static Expr<BigDecimal> divide(Expr<BigDecimal> left, Expr<? extends Number> right) {
        return new Nodes.Arithmetic<>(left.type(), "/", List.of(left, right));
    }

    public static <N extends Number> Expr<N> abs(Expr<N> e) { return Nodes.call(e.type(), "ABS", List.of(e)); }

    public static <N extends Number> Expr<N> floor(Expr<N> e) { return Nodes.call(e.type(), "FLOOR", List.of(e)); }

    public static <N extends Number> Expr<N> ceil(Expr<N> e) { return Nodes.call(e.type(), "CEIL", List.of(e)); }

    public static <N extends Number> Expr<N> round(Expr<N> e, int scale) { return Nodes.call(e.type(), "ROUND", List.of(e, lit(scale))); }

    private static <N extends Number> Expr<N> arithmetic(String operator, Expr<N> first, Expr<? extends Number>[] rest) {
        List<Expr<?>> operands = new ArrayList<>();
        operands.add(first);
        operands.addAll(Arrays.asList(rest));
        return new Nodes.Arithmetic<>(first.type(), operator, operands);
    }

    // ---------- aggregates ----------

    public static Aggregate<Long> count() { return new Aggregate<>(SqlType.of(Long.class), "COUNT", false, List.of()); }

    public static Aggregate<Long> count(Expr<?> e) { return new Aggregate<>(SqlType.of(Long.class), "COUNT", false, List.of(e)); }

    public static Aggregate<Long> countDistinct(Expr<?> e) { return new Aggregate<>(SqlType.of(Long.class), "COUNT", true, List.of(e)); }

    public static <N extends Number> Aggregate<N> sum(Expr<N> e) { return new Aggregate<>(e.type(), "SUM", false, List.of(e)); }

    public static Aggregate<BigDecimal> avg(Expr<? extends Number> e) {
        return new Aggregate<>(SqlType.of(BigDecimal.class), "AVG", false, List.of(e));
    }

    public static <T> Aggregate<T> min(Expr<T> e) { return new Aggregate<>(e.type(), "MIN", false, List.of(e)); }

    public static <T> Aggregate<T> max(Expr<T> e) { return new Aggregate<>(e.type(), "MAX", false, List.of(e)); }

    /** 1 on the subtotal rows of {@code groupByRollup} where the key is rolled up, 0 elsewhere. */
    public static Expr<Integer> grouping(Expr<?> key) { return Nodes.call(SqlType.of(Integer.class), "GROUPING", List.of(key)); }

    // ---------- window functions ----------

    public static WindowFunction<Long> rowNumber() { return new WindowFunction<>(SqlType.of(Long.class), "ROW_NUMBER", List.of()); }

    public static WindowFunction<Long> rank() { return new WindowFunction<>(SqlType.of(Long.class), "RANK", List.of()); }

    public static WindowFunction<Long> denseRank() { return new WindowFunction<>(SqlType.of(Long.class), "DENSE_RANK", List.of()); }

    public static <T> WindowFunction<T> lag(Expr<T> e, int offset) { return new WindowFunction<>(e.type(), "LAG", List.of(e, lit(offset))); }

    public static <T> WindowFunction<T> lead(Expr<T> e, int offset) { return new WindowFunction<>(e.type(), "LEAD", List.of(e, lit(offset))); }

    // ---------- CASE, CAST, raw SQL, functions, subqueries ----------

    public static <T> Case<T> caseWhen(Cond condition, Expr<T> result) {
        return new Case<T>(result.type(), List.of(), List.of()).when(condition, result);
    }

    @SuppressWarnings("unchecked")
    public static <T> Case<T> caseWhen(Cond condition, T result) {
        return caseWhen(condition, new Nodes.Param<>((SqlType<T>) SqlType.of(result.getClass()), result));
    }

    /** {@code CASE subject WHEN value THEN result ... END}: {@code caseOf(code).when("1", "own").when("2", "supplier").otherwise("other")}. */
    public static <V> SimpleCase.Start<V> caseOf(Expr<V> subject) { return new SimpleCase.Start<>(subject); }

    public static <U> Expr<U> cast(Expr<?> e, CastType<U> type) { return new Nodes.Cast<>(e, type); }

    /** first followed by rest, for dialect functions such as GREATEST that take two or more arguments. */
    @SafeVarargs
    protected static <T> Expr<?>[] twoOrMore(String function, Expr<T> first, Expr<? extends T>... rest) {
        if (rest.length == 0) throw new IllegalArgumentException(function + " needs at least two arguments");
        Expr<?>[] all = new Expr<?>[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** A typed escape hatch: {0}, {1} are the rendered arguments, the result type is declared. */
    public static <T> Expr<T> sql(Class<T> type, String template, Expr<?>... args) {
        return new Nodes.Template<>(SqlType.of(type), template, List.of(args));
    }

    /** A call of a function definition, the way built-in and user functions are made alike. */
    public static <T> Expr<T> call(FunctionDef function, Class<T> type, Expr<?>... args) {
        return Nodes.call(SqlType.of(type), function, List.of(args));
    }

    public static <T> Expr<T> call(FunctionDef function, SqlType<T> type, Expr<?>... args) {
        return Nodes.call(type, function, List.of(args));
    }

    /** A one-column query used as a value. */
    public static <T> Expr<T> scalar(Query<T> subquery) { return new Nodes.Scalar<>(subquery); }

    public static Expr<LocalDate> currentDate() { return new Nodes.Raw<>(SqlType.of(LocalDate.class), "CURRENT_DATE"); }

    public static Expr<LocalDateTime> currentTimestamp() { return new Nodes.Raw<>(SqlType.of(LocalDateTime.class), "CURRENT_TIMESTAMP"); }

    // ---------- several keys or orders from one lambda ----------

    /** Several GROUP BY keys or selectAs columns; a single one needs no wrapper. */
    public static KeyList keys(Expr<?>... exprs) { return new KeyList(List.of(exprs)); }

    /** Several ORDER BY items; a bare expression sorts ascending. */
    public static OrderList orders(OrderItem... items) { return new OrderList(OrderList.of(items)); }
}
