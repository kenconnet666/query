package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Aggregation;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Lock;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Conditions;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.ExprNode;
import org.dpdns.zerodep.query.expr.Family;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.expr.OrderItem;
import org.dpdns.zerodep.query.expr.Window;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprNumber;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.TableProxy;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * PostgreSQL 门面：包含 {@link Sql} 的全部能力，外加 PostgreSQL 专属的函数和子句。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.postgres.Postgres.*;}</pre>
 * 有主操作数的 PostgreSQL 函数经由视图调用：{@code postgres(l.text()).ilike("ea%")}，视图的结果回到核心的类型接口。
 * PostgreSQL 专属函数在其他方言上渲染时会报错。
 */
public final class Postgres extends Sql {
    // 只能在 PostgreSQL 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef CUBE = FunctionDef.only(PostgresDialect.class, "CUBE");
    private static final FunctionDef GREATEST = FunctionDef.only(PostgresDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(PostgresDialect.class, "LEAST");
    private static final FunctionDef GROUPING_SETS = FunctionDef.only(PostgresDialect.class, "GROUPING SETS",
        (w, args) -> w.sql("GROUPING SETS (").join(args, ", ").sql(")"));
    private static final FunctionDef PERCENTILE_CONT = FunctionDef.only(PostgresDialect.class, "PERCENTILE_CONT",
        (w, args) -> w.sql("PERCENTILE_CONT(").render(args.get(0)).sql(") WITHIN GROUP (ORDER BY ").render(args.get(1)).sql(")")).aggregating();
    private static final FunctionDef PERCENTILE_DISC = FunctionDef.only(PostgresDialect.class, "PERCENTILE_DISC",
        (w, args) -> w.sql("PERCENTILE_DISC(").render(args.get(0)).sql(") WITHIN GROUP (ORDER BY ").render(args.get(1)).sql(")")).aggregating();
    private static final FunctionDef MODE = FunctionDef.only(PostgresDialect.class, "MODE",
        (w, args) -> w.sql("MODE() WITHIN GROUP (ORDER BY ").render(args.get(0)).sql(")")).aggregating();
    private static final FunctionDef CORR = FunctionDef.only(PostgresDialect.class, "CORR").aggregating();
    private static final FunctionDef CARDINALITY = FunctionDef.only(PostgresDialect.class, "cardinality");

    /** 只放静态方法，不能实例化。 */
    private Postgres() { }

    // ---------- 数据库 ----------

    /** 用默认配置创建 PostgreSQL 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static PostgresDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 PostgreSQL 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static PostgresDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 创建 PostgreSQL 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。 */
    public static PostgresDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new PostgresDatabase(connections, configure);
    }

    // ---------- 分组键 ----------

    /** CUBE(keys) 作为 GROUP BY 键：对键的每种组合都给出小计。 */
    public static Expr<Object> cube(Expr<?>... keys) { return call(CUBE, Object.class, keys); }

    /** GROUPING SETS ((a, b), (a), ()) 作为 GROUP BY 键：恰好给出所列的分组；空列表表示总计。 */
    @SafeVarargs
    public static Expr<Object> groupingSets(List<? extends Expr<?>>... sets) {
        List<Renderable> args = new ArrayList<>();
        for (List<? extends Expr<?>> set : sets) args.add(w -> w.sql("(").join(set, ", ").sql(")"));
        return new ExprNode<>(SqlType.of(Object.class)) {
            @Override public void render(SqlWriter w) { GROUPING_SETS.render(w, args); }
        };
    }

    // ---------- 子句 ----------

    /** DISTINCT ON (keys)：每个键只取第一行；ORDER BY 必须以相同的键开头。 */
    public static Clause distinctOn(Expr<?>... keys) {
        if (keys.length == 0) throw new IllegalArgumentException("distinctOn() needs at least one key");
        List<Expr<?>> list = List.of(keys);
        return clause(Clause.Position.DISTINCT, w -> w.sql("DISTINCT ON (").join(list, ", ").sql(")"));
    }

    // ---------- 集合运算、窗口帧、递归 CTE、加锁 ----------

    /** INTERSECT ALL：两边都有的行，保留重复（取两边次数的较小值）。规则同 {@code q1.unionAll(q2)}。 */
    public static <R> Query<R> intersectAll(Query<R> left, Query<R> right) { return left.combine("INTERSECT ALL", right); }

    /** EXCEPT ALL：左边有、右边没有的行，按次数相减。规则同 {@code q1.unionAll(q2)}。 */
    public static <R> Query<R> exceptAll(Query<R> left, Query<R> right) { return left.combine("EXCEPT ALL", right); }

    /**
     * RANGE 窗口帧：按排序值的距离取行，排序值相同的行一起进出，例如近 7 天的滚动合计
     * {@code over(w -> w.orderBy(l.createdOn()).frame(range(daysPreceding(6), Bound.currentRow())))}。
     * 有限的偏移要求窗口只有一个排序项；数值偏移用 {@code Bound.preceding(n)}。
     */
    public static Renderable range(Window.Bound start, Window.Bound end) {
        String sql = "RANGE BETWEEN " + start.sql() + " AND " + end.sql();
        return w -> {
            requirePostgres(w);
            w.sql(sql);
        };
    }

    /** RANGE 帧的起点：排序值（日期或时间戳）之前 days 天。 */
    public static Window.Bound daysPreceding(int days) { return Window.Bound.custom("INTERVAL '" + nonNegative(days) + " days' PRECEDING"); }

    /** RANGE 帧的终点：排序值之后 days 天。 */
    public static Window.Bound daysFollowing(int days) { return Window.Bound.custom("INTERVAL '" + nonNegative(days) + " days' FOLLOWING"); }

    /**
     * 递归 CTE：{@code anchor UNION ALL step(自己)}，step 里 {@code from(self)} 取上一轮的行，例如沿上级编号展开组织树：
     * <pre>{@code
     * Cte<Node> tree = recursiveCte("tree",
     *     from(Org.class).where(o -> o.parent().isNull()).select(o -> cols(o.id(), lit(1).as("depth"))).into(Node.class),
     *     self -> from(self).join(Org.class, (t, o) -> o.parent().eq(t.col(Node::id)))
     *         .select((t, o) -> cols(o.id(), t.col(Node::depth).plus(lit(1)).as("depth"))).into(Node.class));
     * }</pre>
     * 语句里有递归 CTE 时写成 {@code WITH RECURSIVE}；step 必须有终止条件。
     */
    public static <E> Cte<E> recursiveCte(String name, Query<E> anchor, Function<Cte<E>, Query<E>> step) {
        return Cte.recursive(name, anchor, step, PostgresDialect.class);
    }

    /** 共享锁读 {@code FOR SHARE}：别人可以读、也可以再加共享锁，但改不了；lock 同 {@code forUpdate(lock)}。 */
    public static Clause forShare(Lock lock) { return lockClause("FOR SHARE", lock, List.of()); }

    /** 只锁 tables 这几张表的行：{@code .with((l, m) -> forUpdateOf(Lock.SKIP_LOCKED, l))} 写成 {@code FOR UPDATE OF 别名 SKIP LOCKED}。 */
    public static Clause forUpdateOf(Lock lock, Entity... tables) {
        if (tables.length == 0) throw new IllegalArgumentException("forUpdateOf(...) needs at least one table");
        return lockClause("FOR UPDATE", lock, List.of(tables));
    }

    /** {@code FOR UPDATE / FOR SHARE [OF 别名, ...] [NOWAIT | SKIP LOCKED]}。 */
    private static Clause lockClause(String mode, Lock lock, List<Entity> tables) {
        Objects.requireNonNull(lock, "lock");
        List<Ref<?>> refs = tables.stream().<Ref<?>>map(TableProxy::refOf).toList();
        return clause(Clause.Position.END, w -> {
            w.sql(mode);
            for (int i = 0; i < refs.size(); i++) w.sql(i == 0 ? " OF " : ", ").alias(refs.get(i).alias(w));
            if (lock == Lock.NOWAIT) w.sql(" NOWAIT");
            else if (lock == Lock.SKIP_LOCKED) w.sql(" SKIP LOCKED");
        });
    }

    /** 校验天数不为负。 */
    private static int nonNegative(int days) {
        if (days < 0) throw new IllegalArgumentException("Frame offsets must not be negative");
        return days;
    }

    // ---------- 视图：有主操作数的 PostgreSQL 函数 ----------

    /** 文本上的 PostgreSQL 函数：{@code postgres(l.text()).ilike("ea%")}、{@code postgres(t.payload()).jsonText("order", "id")}。 */
    public static <T> ViewText<T> postgres(ExprText<T> e) { return new ViewText<>(e); }

    /** 任意值上的 PostgreSQL 函数：{@code postgres(l.unit()).eqAny(units)}。 */
    public static <T> ViewAny<T> postgres(Expr<T> e) { return new ViewAny<>(e); }

    /** 任意值上的 PostgreSQL 函数，由 {@code postgres(e)} 得到。 */
    public static class ViewAny<T> {
        /** 视图的主操作数。 */
        final Expr<T> e;

        ViewAny(Expr<T> e) { this.e = Objects.requireNonNull(e, "e"); }

        /**
         * 与一组值中任一个相等：{@code e = ANY(?)}，写进当前的条件块。整个集合绑定成一个数组参数，不像 {@code IN (?, ?, ...)} 那样随个数变长，
         * 语句可以复用执行计划，也不受参数个数上限的限制。值的类型支持文本、整数、长整数、小数和日期。空集合恒假。
         */
        public void eqAny(Collection<? extends T> values) {
            if (SkipEmpty.skips(values)) {
                Conditions.add(Cond.EMPTY);
                return;
            }
            Class<?> element = e.type().storageType();
            Object array = values.toArray((Object[]) Array.newInstance(element, 0));
            Expr<Object> param = arrayParam(array);
            Conditions.add(cond(w -> w.render(e).sql(" = ANY(").render(param).sql(")")));
        }
    }

    /** 文本上的 PostgreSQL 函数，由 {@code postgres(文本表达式)} 得到。 */
    public static final class ViewText<T> extends ViewAny<T> {
        ViewText(ExprText<T> e) { super(e); }

        /** ILIKE：不区分大小写的 LIKE，写进当前的条件块。skipEmpty 下模式为空时没有条件。 */
        public void ilike(String pattern) {
            if (skipped(pattern)) {
                Conditions.add(Cond.EMPTY);
                return;
            }
            Expr<String> p = val(pattern, SqlType.of(String.class));
            Conditions.add(cond(w -> w.render(e).sql(" ILIKE ").render(p)));
        }

        /** JSONB 里一个路径上的文本 {@code doc #>> ARRAY[?, ...]::TEXT[]}，路径不存在或是 JSON null 时为 NULL；路径各段都绑定成参数。 */
        public ExprText<String> jsonText(String... path) {
            if (path.length == 0) throw new IllegalArgumentException("jsonText(...) needs a path");
            List<Expr<String>> keys = Arrays.stream(path).<Expr<String>>map(p -> val(Objects.requireNonNull(p, "path"))).toList();
            return ExprText.of(new ExprNode<>(SqlType.of(String.class)) {
                @Override public void render(SqlWriter w) {
                    requirePostgres(w);
                    w.sql("(").render(e).sql(" #>> CAST(ARRAY[").join(keys, ", ").sql("] AS TEXT[]))");
                }
            });
        }

        /** JSONB 包含 json 这段文档：{@code doc @> CAST(? AS JSONB)}，例如 {@code postgres(t.payload()).jsonContains("{\"status\":\"open\"}")}；能用 GIN 索引。 */
        public void jsonContains(String json) {
            if (SkipEmpty.skips(json)) {
                Conditions.add(Cond.EMPTY);
                return;
            }
            Expr<String> value = val(json);
            Conditions.add(cond(w -> w.render(e).sql(" @> CAST(").render(value).sql(" AS JSONB)")));
        }

        /** JSONB 的顶层有这个键：{@code jsonb_exists(doc, ?)}（即 {@code ?} 运算符，JDBC 里不能直接写问号）。 */
        public void jsonHasKey(String key) {
            if (SkipEmpty.skips(key)) {
                Conditions.add(Cond.EMPTY);
                return;
            }
            Expr<String> value = val(key);
            Conditions.add(cond(w -> w.sql("jsonb_exists(").render(e).sql(", ").render(value).sql(")")));
        }
    }

    // ---------- 数组 ----------

    /** 数组列包含 values 的全部元素：{@code arr @> ?}，写进当前的条件块。 */
    public static <T> void arrayContains(Expr<T[]> array, T[] values) { Conditions.add(arrayOp(array, " @> ", values)); }

    /** 数组列与 values 至少有一个共同元素：{@code arr && ?}，写进当前的条件块。 */
    public static <T> void arrayOverlaps(Expr<T[]> array, T[] values) { Conditions.add(arrayOp(array, " && ", values)); }

    /** 数组的元素个数 {@code cardinality(arr)}，空数组为 0。 */
    public static ExprInteger<Integer> cardinality(Expr<?> array) { return ExprInteger.of(Nodes.call(SqlType.of(Integer.class), CARDINALITY, List.of(array))); }

    /** 把组内的值收集成数组 {@code ARRAY_AGG(e ORDER BY ...)}；数组类型由 elements 的类给出，例如 {@code String[].class}。 */
    public static <T> Expr<T[]> arrayAgg(Expr<T> e, Class<T[]> elements, OrderItem... order) {
        List<Renderable> args = new ArrayList<>();
        args.add(e);
        args.addAll(orders(order));
        return new ExprNode<>(SqlType.of(elements)) {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                Aggregation.aggregate(w);
                w.sql("ARRAY_AGG(").render(args.get(0));
                if (args.size() > 1) w.sql(" ORDER BY ").join(args.subList(1, args.size()), ", ");
                w.sql(")");
            }
        };
    }

    /** {@code arr op ?}，values 绑定成同类型的数组参数。 */
    private static <T> Cond arrayOp(Expr<T[]> array, String op, T[] values) {
        Expr<Object> param = arrayParam(Objects.requireNonNull(values, "values"));
        return cond(w -> w.render(array).sql(op).render(param));
    }

    /** 绑定成数组的参数；PostgresDialect 的类型注册表负责 createArrayOf。 */
    @SuppressWarnings("unchecked")
    private static Expr<Object> arrayParam(Object array) { return new Nodes.Param<>((SqlType<Object>) SqlType.of(array.getClass()), array); }

    // ---------- JSONB ----------

    /** 写入 JSONB 列的值 {@code CAST(? AS JSONB)}：{@code update(T.class).set(t -> t.payload().to(jsonb(text)))}。 */
    public static ExprText<String> jsonb(String json) {
        Expr<String> value = val(Objects.requireNonNull(json, "json"));
        return ExprText.of(new ExprNode<>(SqlType.of(String.class)) {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                w.sql("CAST(").render(value).sql(" AS JSONB)");
            }
        });
    }

    // ---------- 全文检索 ----------

    /**
     * 全文检索 {@code to_tsvector(配置, 文本) @@ plainto_tsquery(配置, ?)}，写进当前的条件块：query 里的词都要出现（忽略大小写、按配置做词干化）。
     * config 是文本检索配置，例如 {@code "simple"}、{@code "english"}；建 GIN 索引时用同一个配置。skipEmpty 下 query 为空时没有条件。
     */
    public static void textMatches(String config, ExprText<?> document, String query) { Conditions.add(textSearch("plainto_tsquery", config, document, query)); }

    /** 同 {@link #textMatches}，但 query 用网页搜索的写法（{@code websearch_to_tsquery}）：引号短语、{@code or}、{@code -排除}。 */
    public static void webMatches(String config, ExprText<?> document, String query) { Conditions.add(textSearch("websearch_to_tsquery", config, document, query)); }

    /** 相关度 {@code ts_rank(to_tsvector(配置, 文本), plainto_tsquery(配置, ?))}，用来排序；不匹配的行也有分数，筛选要另用 {@link #textMatches}。 */
    public static ExprDouble<Double> textRank(String config, ExprText<?> document, String query) {
        Expr<String> cfg = val(Objects.requireNonNull(config, "config"));
        Expr<String> q = val(Objects.requireNonNull(query, "query"));
        return ExprDouble.of(new ExprNode<>(SqlType.of(Double.class)) {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                w.sql("CAST(ts_rank(to_tsvector(CAST(").render(cfg).sql(" AS regconfig), ").render(document)
                    .sql("), plainto_tsquery(CAST(").render(cfg).sql(" AS regconfig), ").render(q).sql(")) AS DOUBLE PRECISION)");
            }
        });
    }

    /** 全文检索条件；tsquery 是生成查询的函数名。 */
    private static Cond textSearch(String tsquery, String config, ExprText<?> document, String query) {
        if (SkipEmpty.skips(query)) return Cond.EMPTY;
        Expr<String> cfg = val(Objects.requireNonNull(config, "config"));
        Expr<String> q = val(query);
        return cond(w -> w.sql("to_tsvector(CAST(").render(cfg).sql(" AS regconfig), ").render(document).sql(") @@ ").sql(tsquery)
            .sql("(CAST(").render(cfg).sql(" AS regconfig), ").render(q).sql(")"));
    }

    // ---------- 统计 ----------

    /** 连续百分位数 {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)}，在相邻两行之间插值；p 取 0 到 1，中位数是 0.5。只能作聚合。 */
    public static ExprDouble<Double> percentileCont(double p, ExprNumber<?> e) {
        return ExprDouble.of(Nodes.call(SqlType.of(Double.class), PERCENTILE_CONT, List.of(lit(fraction(p)), e.cast(CastType.doublePrecision()))));
    }

    /** 离散百分位数 {@code PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)}：取排序后第一个累计比例不小于 p 的值，结果与 e 同一类型。只能作聚合。 */
    public static <T, F extends Expr<T>> F percentileDisc(double p, Family<T, F> e) {
        return e.retype(Nodes.call(e.type(), PERCENTILE_DISC, List.of(lit(fraction(p)), e)));
    }

    /** 众数 {@code MODE() WITHIN GROUP (ORDER BY e)}：出现次数最多的值，并列时取最小的，结果与 e 同一类型。只能作聚合。 */
    public static <T, F extends Expr<T>> F mode(Family<T, F> e) { return e.retype(Nodes.call(e.type(), MODE, List.of(e))); }

    /** 相关系数 {@code CORR(y, x)}，取值 -1 到 1。 */
    public static ExprDouble<Double> corr(ExprNumber<?> y, ExprNumber<?> x) { return ExprDouble.of(Nodes.call(SqlType.of(Double.class), CORR, List.of(y, x))); }

    /** 校验百分位数在 0 到 1 之间。 */
    private static double fraction(double p) {
        if (!(p >= 0 && p <= 1)) throw new IllegalArgumentException("A percentile must be between 0 and 1, got " + p);
        return p;
    }

    // ---------- 内部 ----------

    /** 构造一个只属于 PostgreSQL 方言的子句，放在 {@code position} 指定的位置。 */
    private static Clause clause(Clause.Position position, Consumer<SqlWriter> body) {
        return new Clause() {
            @Override public Position position() { return position; }

            @Override public Class<? extends Dialect> dialect() { return PostgresDialect.class; }

            @Override public void render(SqlWriter w) { body.accept(w); }
        };
    }

    /** PostgreSQL 专属的条件；在其他方言上渲染会明确报错。 */
    private static Cond cond(Consumer<SqlWriter> body) {
        return new Cond() {
            @Override public void render(SqlWriter w) {
                requirePostgres(w);
                body.accept(w);
            }
        };
    }

    /** 渲染目标不是 PostgreSQL 时抛出异常。 */
    private static void requirePostgres(SqlWriter w) {
        if (!(w.dialect() instanceof PostgresDialect)) {
            throw new UnsupportedOperationException("A PostgreSQL-only operation cannot run on " + w.dialect().name());
        }
    }

    // ---------- 多个同类型参数 ----------

    /** GREATEST(a, b, ...)，结果与 first 同一类型，常量写作 {@code val(x)}：忽略 NULL 参数，仅当全部为 NULL 时结果才是 NULL。 */
    @SafeVarargs
    public static <T, F extends Expr<T>> F greatest(Family<T, F> first, Expr<? extends T>... rest) {
        return first.retype(call(GREATEST, first.type(), twoOrMore("GREATEST", first, rest)));
    }

    /** LEAST(a, b, ...)，结果与 first 同一类型，常量写作 {@code val(x)}：忽略 NULL 参数，仅当全部为 NULL 时结果才是 NULL。 */
    @SafeVarargs
    public static <T, F extends Expr<T>> F least(Family<T, F> first, Expr<? extends T>... rest) {
        return first.retype(call(LEAST, first.type(), twoOrMore("LEAST", first, rest)));
    }

    // ---------- 联表写入 ----------

    /** 联表更新：SET 和 WHERE 可以引用 joined 表，写成 UPDATE ... FROM；见 {@link JoinUpdate}。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> updateJoin(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        return JoinUpdate.of(table, joined, on);
    }

    /** 联表删除：WHERE 可以引用 joined 表，写成 DELETE ... USING；见 {@link JoinDelete}。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> deleteJoin(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        return JoinDelete.of(table, joined, on);
    }
}
