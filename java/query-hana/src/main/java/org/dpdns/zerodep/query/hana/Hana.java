package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.dialect.Clause;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
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
import org.dpdns.zerodep.query.expr.WindowFunction;
import org.dpdns.zerodep.query.expr.typed.ExprDate;
import org.dpdns.zerodep.query.expr.typed.ExprDouble;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprNumber;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.scope.SkipEmpty;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.Source;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.write.JoinDelete;
import org.dpdns.zerodep.query.write.JoinUpdate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * HANA 门面：包含 {@link Sql} 的全部能力，外加 HANA 专属的函数和子句。一次静态导入即可使用全部词汇：
 * <pre>{@code import static org.dpdns.zerodep.query.hana.Hana.*;}</pre>
 * 有主操作数的 HANA 函数经由视图调用：{@code hana(l.createdOn()).toVarchar("YYYYMMDD")}、{@code hana(l.delivery()).toDate("YYYYMMDD")}，
 * 视图的结果回到核心的类型接口，可以接着写。HANA 专属函数在其他方言上渲染时会报错。
 */
public final class Hana extends Sql {
    // 只能在 HANA 方言上渲染的函数；在其他方言上渲染会报错。
    private static final FunctionDef TO_VARCHAR = FunctionDef.only(HanaDialect.class, "TO_VARCHAR");
    private static final FunctionDef TO_DATE = FunctionDef.only(HanaDialect.class, "TO_DATE");
    private static final FunctionDef TO_NVARCHAR = FunctionDef.only(HanaDialect.class, "TO_NVARCHAR");
    private static final FunctionDef LENGTH = FunctionDef.only(HanaDialect.class, "LENGTH");
    private static final FunctionDef TO_INTEGER = FunctionDef.only(HanaDialect.class, "TO_INTEGER");
    private static final FunctionDef GREATEST = FunctionDef.only(HanaDialect.class, "GREATEST");
    private static final FunctionDef LEAST = FunctionDef.only(HanaDialect.class, "LEAST");
    private static final FunctionDef CUBE = FunctionDef.only(HanaDialect.class, "CUBE");
    private static final FunctionDef MEDIAN = FunctionDef.only(HanaDialect.class, "MEDIAN");
    private static final FunctionDef SCORE = FunctionDef.only(HanaDialect.class, "SCORE");
    private static final FunctionDef CORR = FunctionDef.only(HanaDialect.class, "CORR");
    private static final FunctionDef PERCENTILE_CONT = FunctionDef.only(HanaDialect.class, "PERCENTILE_CONT",
        (w, args) -> w.sql("PERCENTILE_CONT(").render(args.get(0)).sql(") WITHIN GROUP (ORDER BY ").render(args.get(1)).sql(")"));
    private static final FunctionDef PERCENTILE_DISC = FunctionDef.only(HanaDialect.class, "PERCENTILE_DISC",
        (w, args) -> w.sql("PERCENTILE_DISC(").render(args.get(0)).sql(") WITHIN GROUP (ORDER BY ").render(args.get(1)).sql(")"));
    private static final FunctionDef GROUPING_SETS = FunctionDef.only(HanaDialect.class, "GROUPING SETS",
        (w, args) -> w.sql("GROUPING SETS (").join(args, ", ").sql(")"));

    /** 只放静态方法，不能实例化。 */
    private Hana() { }

    // ---------- 数据库 ----------

    /** 用默认配置创建 HANA 数据库实例，每条语句从 {@code dataSource} 取一个连接。 */
    public static HanaDatabase database(DataSource dataSource) { return database(ConnectionProvider.of(dataSource), null); }

    /** 创建 HANA 数据库实例，每条语句从 {@code dataSource} 取一个连接，并用 {@code configure} 调整配置。 */
    public static HanaDatabase database(DataSource dataSource, Consumer<Database.Config> configure) {
        return database(ConnectionProvider.of(dataSource), configure);
    }

    /** 创建 HANA 数据库实例，连接由 {@code connections} 提供；{@code configure} 可为 null。 */
    public static HanaDatabase database(ConnectionProvider connections, Consumer<Database.Config> configure) {
        return new HanaDatabase(connections, configure);
    }

    // ---------- 数据来源 ----------

    /**
     * 带输入参数的计算视图：{@code fromView(SalesView.class, Map.of("P_YEAR", "2026"))} 写成
     * {@code FROM "S"."CV_SALES" (PLACEHOLDER."$$P_YEAR$$" => ?)}。视图的列声明成带 {@code @Table} 的表接口，
     * 参数都绑定；值为 null 的参数不写，由视图的默认值决定。
     */
    public static <T extends Entity> From1<T> fromView(Class<T> view, Map<String, ?> placeholders) {
        Ref<T> ref = Ref.of(new HanaSources.Parameterized<>(view, placeholders, false));
        return From1.start(ref, ref.param());
    }

    /** 表函数：{@code fromFunction(OpenItems.class, Map.of("P_UNIT", "EA"))} 写成 {@code FROM "S"."FN" ("P_UNIT" => ?)}，命名实参，可以省略有默认值的参数。 */
    public static <T extends Entity> From1<T> fromFunction(Class<T> function, Map<String, ?> arguments) {
        Ref<T> ref = Ref.of(new HanaSources.Parameterized<>(function, arguments, true));
        return From1.start(ref, ref.param());
    }

    /**
     * 层级：{@code from(hierarchy(Node.class, 来源查询))}。来源查询要有 nodeId 和 parentId 两列（类型相同，根的 parentId 为 NULL），
     * 写成 {@code HIERARCHY(SOURCE (...) SIBLING ORDER BY NODE_ID)}。结果类里名为 hierarchyRank、hierarchyLevel（从 1 起）、
     * hierarchyParentRank（根为 0）、hierarchyRootRank、hierarchyTreeSize、hierarchyIsCycle、hierarchyIsOrphan 的属性读层级函数生成的列，
     * 其余属性读来源查询的同名列。
     */
    public static <H> Source<H> hierarchy(Class<H> shape, Query<?> source) {
        return new HanaSources.Hierarchy<>(shape, source, HanaSources.Navigation.TREE, List.of());
    }

    /** 从 start 这些节点出发的后代（含自身），用 {@code HIERARCHY_DESCENDANTS}；另有 startRank、hierarchyDistance（自身为 0）两列。 */
    public static <H> Source<H> descendants(Class<H> shape, Query<?> source, Collection<?> start) {
        return new HanaSources.Hierarchy<>(shape, source, HanaSources.Navigation.DESCENDANTS, List.copyOf(start));
    }

    /** 从 start 这些节点出发的祖先（含自身），用 {@code HIERARCHY_ANCESTORS}；hierarchyDistance 为负数。 */
    public static <H> Source<H> ancestors(Class<H> shape, Query<?> source, Collection<?> start) {
        return new HanaSources.Hierarchy<>(shape, source, HanaSources.Navigation.ANCESTORS, List.copyOf(start));
    }

    // ---------- 视图：有主操作数的 HANA 函数 ----------

    /** 文本上的 HANA 函数：{@code hana(l.delivery()).toDate("YYYYMMDD")}、{@code hana(l.text()).likeRegexpr("^A")}。 */
    public static <T> ViewString<T> hana(ExprText<T> e) { return new ViewString<>(e); }

    /** 数值上的 HANA 函数：{@code hana(l.quantity()).median()}。 */
    public static <T> ViewNumber<T> hana(ExprNumber<T> e) { return new ViewNumber<>(e); }

    /** 任意值上的 HANA 函数：{@code hana(e.onDay()).toVarchar("YYYYMMDD")}。 */
    public static <T> ViewAny<T> hana(Expr<T> e) { return new ViewAny<>(e); }

    /** 任意值上的 HANA 函数，由 {@code hana(e)} 得到。 */
    public static class ViewAny<T> {
        /** 视图的主操作数。 */
        final Expr<T> e;

        ViewAny(Expr<T> e) { this.e = Objects.requireNonNull(e, "e"); }

        /** TO_VARCHAR(this, format)，例如日期写成 {@code toVarchar("YYYYMMDD")}。 */
        public ExprText<String> toVarchar(String format) { return ExprText.of(call(TO_VARCHAR, String.class, e, lit(format))); }

        /** TO_NVARCHAR(this, format)，{@link #toVarchar} 的 Unicode 版本。 */
        public ExprText<String> toNvarchar(String format) { return ExprText.of(call(TO_NVARCHAR, String.class, e, lit(format))); }

        /** TO_NVARCHAR(this)，不带格式。 */
        public ExprText<String> toNvarchar() { return ExprText.of(call(TO_NVARCHAR, String.class, e)); }
    }

    /** 文本上的 HANA 函数，由 {@code hana(文本表达式)} 得到。 */
    public static final class ViewString<T> extends ViewAny<T> {
        ViewString(ExprText<T> e) { super(e); }

        /** TO_DATE(this, format)：按 format 把文本解析为日期。 */
        public ExprDate<LocalDate> toDate(String format) { return ExprDate.of(call(TO_DATE, LocalDate.class, e, lit(format))); }

        /** TO_INTEGER(this)，也可写作 TO_INT：文本不是数字时查询会失败。 */
        public ExprInteger<Integer> toInteger() { return ExprInteger.of(call(TO_INTEGER, Integer.class, e)); }

        /** LENGTH：HANA 按 UTF-16 码元计数，所以 BMP 之外的字符算 2。 */
        public ExprInteger<Integer> length() { return ExprInteger.of(call(LENGTH, Integer.class, e)); }

        /** {@code this LIKE_REGEXPR pattern}，HANA 的正则谓词，写进当前的条件块；skipEmpty 下模式为空时没有条件。 */
        public void likeRegexpr(String pattern) {
            if (skipped(pattern)) {
                Conditions.add(Cond.EMPTY);
                return;
            }
            Expr<String> p = val(pattern, SqlType.of(String.class));
            Conditions.add(hanaCond(w -> w.render(e).sql(" LIKE_REGEXPR ").render(p)));
        }

        /**
         * JSON 文档里的一个标量 {@code JSON_VALUE(doc, 'path' RETURNING NVARCHAR(5000) ERROR ON ERROR)}，例如 {@code hana(t.payload()).jsonValue("$.order.id")}。
         * HANA 要求路径是字面量，所以路径写在代码里，不能来自用户输入；值超过 5000 个字符时报错而不是截断。
         */
        public ExprText<String> jsonValue(String path) {
            Objects.requireNonNull(path, "path");
            return ExprText.of(new ExprNode<>(SqlType.of(String.class)) {
                @Override public void render(SqlWriter w) {
                    HanaSources.requireHana(w);
                    w.sql("JSON_VALUE(").render(e).sql(", ").literal(path).sql(" RETURNING NVARCHAR(5000) ERROR ON ERROR)");
                }
            });
        }
    }

    /** 数值上的 HANA 函数，由 {@code hana(数值表达式)} 得到。 */
    public static final class ViewNumber<T> extends ViewAny<T> {
        ViewNumber(ExprNumber<T> e) { super(e); }

        /** TO_INTEGER(this)：小数截成整数。 */
        public ExprInteger<Integer> toInteger() { return ExprInteger.of(call(TO_INTEGER, Integer.class, e)); }

        /** 中位数 {@code MEDIAN(this)}（聚合），偶数行时取中间两个值的平均；参数先转成 DOUBLE。 */
        public ExprDouble<Double> median() {
            return ExprDouble.of(Nodes.call(SqlType.of(Double.class), MEDIAN, List.of(e.cast(CastType.doublePrecision()))));
        }
    }

    // ---------- 全文检索 ----------

    /**
     * 全文检索 {@code CONTAINS(列, ?)}，多列写成 {@code CONTAINS((a, b), ?)}，写进当前的条件块；搜索词是 HANA 的 freestyle 语法（空格是与、OR、引号短语）。
     * 只能用于列存表；skipEmpty 下搜索词为空时没有条件。与 {@link #score()} 配合排序。
     */
    public static void contains(String term, ExprText<?>... columns) { Conditions.add(search(term, columns, null)); }

    /** 模糊检索 {@code CONTAINS(列, ?, FUZZY(threshold))}，threshold 取 0 到 1，越大越严格，常用 0.8。 */
    public static void containsFuzzy(double threshold, String term, ExprText<?>... columns) {
        if (!(threshold >= 0 && threshold <= 1)) throw new IllegalArgumentException("A fuzzy threshold must be between 0 and 1, got " + threshold);
        Conditions.add(search(term, columns, "FUZZY(" + threshold + ")"));
    }

    /** 查询里全部 CONTAINS 的综合相关度 {@code SCORE()}，0 到 1。 */
    public static ExprDouble<Double> score() { return ExprDouble.of(Nodes.call(SqlType.of(Double.class), SCORE, List.of())); }

    /** CONTAINS 条件；option 是 FUZZY(...) 之类的固定文本。 */
    private static Cond search(String term, ExprText<?>[] columns, String option) {
        if (columns.length == 0) throw new IllegalArgumentException("contains(...) needs at least one column");
        if (SkipEmpty.skips(term)) return Cond.EMPTY;
        Expr<String> value = val(term, SqlType.of(String.class));
        List<ExprText<?>> list = List.of(columns);
        return hanaCond(w -> {
            w.sql("CONTAINS(");
            if (list.size() == 1) w.render(list.get(0));
            else w.sql("(").join(list, ", ").sql(")");
            w.sql(", ").render(value);
            if (option != null) w.sql(", ").sql(option);
            w.sql(")");
        });
    }

    /** HANA 专属的条件；在其他方言上渲染会明确报错。 */
    private static Cond hanaCond(Consumer<SqlWriter> body) {
        return new Cond() {
            @Override public void render(SqlWriter w) {
                HanaSources.requireHana(w);
                body.accept(w);
            }
        };
    }

    // ---------- 统计 ----------

    /**
     * 连续百分位数 {@code PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY e)}，再加 {@code .over(w -> w.partitionBy(...))}：
     * HANA 只把它当窗口函数（PostgreSQL 只当聚合），分组的百分位数用 {@code partitionBy} 加 {@code distinct()}。p 取 0 到 1。
     */
    public static WindowFunction<Double, ExprDouble<Double>> percentileCont(double p, ExprNumber<?> e) {
        return new WindowFunction<>(SqlType.of(Double.class), PERCENTILE_CONT, List.of(lit(fraction(p)), e.cast(CastType.doublePrecision())), ExprDouble::of);
    }

    /** 离散百分位数 {@code PERCENTILE_DISC(p) WITHIN GROUP (ORDER BY e)}，再加 {@code .over(...)}；结果与 e 同一类型，规则同 {@link #percentileCont}。 */
    public static <T, F extends Expr<T>> WindowFunction<T, F> percentileDisc(double p, Family<T, F> e) {
        return new WindowFunction<>(e.type(), PERCENTILE_DISC, List.of(lit(fraction(p)), e), e::retype);
    }

    /** 相关系数 {@code CORR(y, x)}，取值 -1 到 1。 */
    public static ExprDouble<Double> corr(ExprNumber<?> y, ExprNumber<?> x) { return ExprDouble.of(Nodes.call(SqlType.of(Double.class), CORR, List.of(y, x))); }

    /** 校验百分位数在 0 到 1 之间。 */
    private static double fraction(double p) {
        if (!(p >= 0 && p <= 1)) throw new IllegalArgumentException("A percentile must be between 0 and 1, got " + p);
        return p;
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

    /** 语句末尾的 WITH HINT(...)。 */
    public static Clause hint(String... hints) {
        if (hints.length == 0) throw new IllegalArgumentException("hint() needs at least one hint");
        String text = "WITH HINT(" + String.join(", ", hints) + ")";
        return new Clause() {
            @Override public Position position() { return Position.END; }

            @Override public Class<? extends Dialect> dialect() { return HanaDialect.class; }

            @Override public void render(SqlWriter w) { w.sql(text); }
        };
    }

    // ---------- 多个同类型参数 ----------

    /** GREATEST(a, b, ...)，结果与 first 同一类型，常量写作 {@code val(x)}：只要有一个参数为 NULL，结果就是 NULL。 */
    @SafeVarargs
    public static <T, F extends Expr<T>> F greatest(Family<T, F> first, Expr<? extends T>... rest) {
        return first.retype(call(GREATEST, first.type(), twoOrMore("GREATEST", first, rest)));
    }

    /** LEAST(a, b, ...)，结果与 first 同一类型，常量写作 {@code val(x)}：只要有一个参数为 NULL，结果就是 NULL。 */
    @SafeVarargs
    public static <T, F extends Expr<T>> F least(Family<T, F> first, Expr<? extends T>... rest) {
        return first.retype(call(LEAST, first.type(), twoOrMore("LEAST", first, rest)));
    }

    // ---------- 联表写入 ----------

    /** 联表更新：SET 和 WHERE 可以引用 joined 表，写成按主键对上行的 MERGE（被改表要有 {@code @Id}）；见 {@link JoinUpdate}。 */
    public static <T extends Entity, S extends Entity> JoinUpdate<T, S> updateJoin(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        return JoinUpdate.of(table, joined, on);
    }

    /** 联表删除：WHERE 可以引用 joined 表，写成 DELETE ... WHERE EXISTS；见 {@link JoinDelete}。 */
    public static <T extends Entity, S extends Entity> JoinDelete<T, S> deleteJoin(Class<T> table, Class<S> joined, BiConsumer<T, S> on) {
        return JoinDelete.of(table, joined, on);
    }
}
