package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;

/** 内置的表达式节点和条件节点。包内可见：用户通过公开 API 构造它们，不直接使用本类。 */
public final class Nodes {
    private Nodes() { }

    // ---------- expressions ----------

    /** 绑定参数。默认渲染为 {@code ?} 占位符，写入器要求内联常量时渲染为字面量。 */
    public static final class Param<T> extends Expr<T> {
        private final T value;

        public Param(SqlType<T> type, T value) {
            super(type);
            this.value = value;
        }

        /** 写入器内联常量且方言能表示该值时写字面量（取转换后的存储值），否则写绑定参数。 */
        @Override public void render(SqlWriter w) {
            Object stored = type().toDatabase(value);
            if (w.inlining() && w.dialect().hasLiteral(stored)) w.literal(stored);
            else w.bind(value, type());
        }
    }

    /** 直接写进 SQL 文本的字面量，只用于代码里的固定常量。 */
    public static final class Literal<T> extends Expr<T> {
        private final T value;

        public Literal(SqlType<T> type, T value) {
            super(type);
            this.value = value;
        }

        /** 写出转换后的存储值，所以类型转换器对字面量和对参数的作用一致。 */
        @Override public void render(SqlWriter w) { w.literal(type().toDatabase(value)); }
    }

    /** 带列名的表达式：渲染与 {@code inner} 完全相同，只是把投影时使用的列名换成 {@code label}。 */
    public static final class Labeled<T> extends Expr<T> {
        final Expr<T> inner;
        private final String label;

        Labeled(Expr<T> inner, String label) {
            super(inner.type());
            if (label == null || label.isBlank()) throw new IllegalArgumentException("A label must not be blank");
            this.inner = inner;
            this.label = label;
        }

        /** 写出被包装的表达式本身，不加别名。 */
        @Override public void render(SqlWriter w) { inner.render(w); }

        /** 返回指定的列名。 */
        @Override public String label() { return label; }
    }

    /** 函数调用。有 {@link FunctionDef} 时由它决定各方言的写法，否则渲染为 {@code NAME(arg, ...)}。 */
    public static final class Call<T> extends Expr<T> {
        private final FunctionDef function;
        private final String name;
        private final List<? extends Expr<?>> args;

        Call(SqlType<T> type, FunctionDef function, String name, List<? extends Expr<?>> args) {
            super(type);
            this.function = function;
            this.name = name;
            this.args = List.copyOf(args);
        }

        /** 有函数定义时交给它渲染，否则按函数名写成普通调用。 */
        @Override public void render(SqlWriter w) {
            if (function != null) function.render(w, args);
            else FunctionDef.call(w, name, args);
        }

        /** 单参数函数（如 UPPER、TRIM）和 COALESCE、NULLIF 沿用第一个参数的列名，cols(...) 里就不必再写 as。 */
        @Override public String nameHint() {
            if (args.isEmpty()) return null;
            return args.size() == 1 || name.equals("COALESCE") || name.equals("NULLIF") ? args.get(0).nameHint() : null;
        }
    }

    /** 兜底的表达式模板：模板里的 {@code {0}}、{@code {1}} 被替换为渲染后的参数。 */
    public static final class Template<T> extends Expr<T> {
        private final String template;
        private final List<? extends Expr<?>> args;

        public Template(SqlType<T> type, String template, List<? extends Expr<?>> args) {
            super(type);
            this.template = template;
            this.args = List.copyOf(args);
        }

        /** 按模板写出，占位符处渲染对应的参数。 */
        @Override public void render(SqlWriter w) { renderTemplate(w, template, args); }
    }

    /** 算术运算：用 {@code operator} 连接各操作数，整体加括号，结果类型由调用方指定。 */
    public static final class Arithmetic<T> extends Expr<T> {
        private final String operator;
        private final List<? extends Expr<?>> operands;

        public Arithmetic(SqlType<T> type, String operator, List<? extends Expr<?>> operands) {
            super(type);
            this.operator = operator;
            this.operands = List.copyOf(operands);
        }

        /** 写成 {@code (a op b op ...)}。括号保证嵌套时优先级不变。 */
        @Override public void render(SqlWriter w) { w.sql("(").join(operands, " " + operator + " ").sql(")"); }
    }

    /** 取负。操作数放在括号里，负数字面量就不会和前面的减号拼成注释。 */
    public static final class Negated<T> extends Expr<T> {
        private final Expr<T> operand;

        public Negated(Expr<T> operand) {
            super(operand.type());
            this.operand = operand;
        }

        /** 写成 {@code -(operand)}。 */
        @Override public void render(SqlWriter w) { w.sql("-(").render(operand).sql(")"); }

        /** 取负沿用原列名。 */
        @Override public String nameHint() { return operand.nameHint(); }
    }

    /** 类型转换 {@code CAST(inner AS type)}，目标类型的写法由方言决定。 */
    public static final class Cast<T> extends Expr<T> {
        private final Expr<?> inner;
        private final CastType<T> target;

        public Cast(Expr<?> inner, CastType<T> target) {
            super(SqlType.of(target.javaType()));
            this.inner = inner;
            this.target = target;
        }

        /** 写成 {@code CAST(inner AS 方言类型)}。 */
        @Override public void render(SqlWriter w) {
            w.sql("CAST(").render(inner).sql(" AS ").sql(w.dialect().castType(target)).sql(")");
        }

        /** 类型转换沿用原列名。 */
        @Override public String nameHint() { return inner.nameHint(); }
    }

    /** 标量子查询：单列查询当作一个值使用，类型取自那一列。 */
    public static final class Scalar<T> extends Expr<T> {
        private final Query<T> query;

        public Scalar(Query<T> query) {
            super(query.singleColumnType());
            this.query = query;
        }

        /** 写成括号包起来的子查询。 */
        @Override public void render(SqlWriter w) {
            w.sql("(");
            QueryRenderer.subquery(w, query);
            w.sql(")");
        }
    }

    /** 原样写出的 SQL 片段，如 {@code NULL}、{@code CURRENT_DATE}；只能放代码里的固定文本。 */
    public static final class Raw<T> extends Expr<T> {
        private final String sql;

        public Raw(SqlType<T> type, String sql) {
            super(type);
            this.sql = sql;
        }

        /** 原样写出 SQL 文本。 */
        @Override public void render(SqlWriter w) { w.sql(sql); }
    }

    /** 按函数名调用，没有方言差异的函数用这个。 */
    public static <T> Expr<T> call(SqlType<T> type, String name, List<? extends Expr<?>> args) { return new Call<>(type, null, name, args); }

    /** 按函数定义调用，由定义决定各方言的写法。 */
    public static <T> Expr<T> call(SqlType<T> type, FunctionDef function, List<? extends Expr<?>> args) {
        return new Call<>(type, function, function.name(), args);
    }

    // ---------- conditions ----------

    /** 用一个写出 SQL 的函数实现的条件，下面各种条件都基于它。 */
    private static final class Of extends Cond {
        private final java.util.function.Consumer<SqlWriter> body;

        Of(java.util.function.Consumer<SqlWriter> body) { this.body = body; }

        /** 执行构造时给定的写出函数。 */
        @Override public void render(SqlWriter w) { body.accept(w); }
    }

    /** 由写出函数构造条件。 */
    public static Cond of(java.util.function.Consumer<SqlWriter> body) { return new Of(body); }

    /** 二元比较：{@code left operator right}。 */
    public static Cond compare(Expr<?> left, String operator, Expr<?> right) { return new Comparison(left, operator, right); }

    /** 比较条件 {@code left op right}；保留两侧，一对多关系的 {@code first(...)} 从中找出连接两张表的等值键。 */
    public static final class Comparison extends Cond {
        /** 左侧表达式。 */
        public final Expr<?> left;
        /** 运算符，例如 {@code =}。 */
        public final String operator;
        /** 右侧表达式。 */
        public final Expr<?> right;

        Comparison(Expr<?> left, String operator, Expr<?> right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        /** 写出 {@code left op right}。 */
        @Override public void render(SqlWriter w) { w.render(left).sql(" " + operator + " ").render(right); }
    }

    /** 用 AND 连接的多个条件；保留各项，供 {@code first(...)} 拆开连接条件。 */
    public static final class Conjunction extends Cond {
        /** 各项条件，都不是空条件。 */
        public final List<Cond> parts;

        Conjunction(List<Cond> parts) { this.parts = List.copyOf(parts); }

        /** 写出 {@code a AND b AND ...}。 */
        @Override public void render(SqlWriter w) { w.join(parts, " AND "); }
    }

    /** 把条件拆成用 AND 连接的各项；不是 AND 的条件就是它自己。 */
    public static List<Cond> conjuncts(Cond c) {
        if (c.isEmpty()) return List.of();
        return c instanceof Conjunction and ? and.parts : List.of(c);
    }

    /** {@code e BETWEEN low AND high}。 */
    public static Cond between(Expr<?> e, Expr<?> low, Expr<?> high) {
        return of(w -> w.render(e).sql(" BETWEEN ").render(low).sql(" AND ").render(high));
    }

    /** 后缀条件，如 {@code e IS NULL}：在 {@code e} 后面接 {@code suffix}。 */
    public static Cond postfix(Expr<?> e, String suffix) { return of(w -> w.render(e).sql(suffix)); }

    /** 恒假条件。用于 IN 空列表等需要一个永远不成立的谓词的场合。 */
    public static final Cond FALSE = of(w -> w.sql("1 = 0"));
    /** 恒真条件。用于 NOT IN 空列表等。 */
    public static final Cond TRUE = of(w -> w.sql("1 = 1"));

    /**
     * IN / NOT IN 一组值。列表为空时 IN 恒假、NOT IN 恒真；超过方言的单个 IN 上限时拆成多段，
     * IN 用 OR、NOT IN 用 AND 连接。
     */
    public static <T> Cond in(Expr<T> e, List<Expr<T>> values, boolean negate) {
        if (values.isEmpty()) return negate ? TRUE : FALSE;
        return of(w -> {
            int max = Math.max(1, w.dialect().maxInListSize());
            if (values.size() <= max) {
                w.render(e).sql(negate ? " NOT IN (" : " IN (").join(values, ", ").sql(")");
                return;
            }
            w.sql("(");
            for (int from = 0; from < values.size(); from += max) {
                if (from > 0) w.sql(negate ? " AND " : " OR ");
                w.render(e).sql(negate ? " NOT IN (" : " IN (").join(values.subList(from, Math.min(values.size(), from + max)), ", ").sql(")");
            }
            w.sql(")");
        });
    }

    /** {@code e IN (子查询)} 或 {@code e NOT IN (子查询)}。 */
    public static Cond inQuery(Expr<?> e, Query<?> query, boolean negate) {
        return of(w -> {
            w.render(e).sql(negate ? " NOT IN (" : " IN (");
            QueryRenderer.subquery(w, query);
            w.sql(")");
        });
    }

    /** {@code EXISTS (子查询)} 或 {@code NOT EXISTS (子查询)}。 */
    public static Cond exists(Query<?> query, boolean negate) {
        return of(w -> {
            w.sql(negate ? "NOT EXISTS (" : "EXISTS (");
            QueryRenderer.subquery(w, query);
            w.sql(")");
        });
    }

    /** 兜底的条件模板，占位符规则同 {@link #renderTemplate}。 */
    public static Cond template(String template, List<? extends Expr<?>> args) {
        List<? extends Expr<?>> copy = List.copyOf(args);
        return of(w -> renderTemplate(w, template, copy));
    }

    /**
     * 全部成立（AND）。空条件被丢弃；全部为空返回 {@link Cond#EMPTY}，只剩一个时原样返回它。
     * 条件为 null 视为调用错误，要表示没有条件应传 {@link Cond#EMPTY}。
     */
    public static Cond all(List<Cond> conds) {
        List<Cond> kept = new ArrayList<>();
        for (Cond c : conds) {
            if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
            if (!c.isEmpty()) kept.add(c);
        }
        if (kept.isEmpty()) return Cond.EMPTY;
        if (kept.size() == 1) return kept.get(0);
        List<Cond> flat = new ArrayList<>();
        for (Cond c : kept) flat.addAll(conjuncts(c));
        return new Conjunction(flat);
    }

    /** 任一成立（OR）。空条件的处理同 {@link #all}；每个操作数单独加括号。 */
    public static Cond any(List<Cond> conds) {
        List<Cond> kept = new ArrayList<>();
        for (Cond c : conds) {
            if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
            if (!c.isEmpty()) kept.add(c);
        }
        if (kept.isEmpty()) return Cond.EMPTY;
        if (kept.size() == 1) return kept.get(0);
        // 每个操作数单独加括号，OR 里的 AND 才不会改变含义
        return of(w -> {
            w.sql("(");
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) w.sql(" OR ");
                w.sql("(").render(kept.get(i)).sql(")");
            }
            w.sql(")");
        });
    }

    /** 取反：{@code NOT (c)}；空条件取反后仍然是空条件。 */
    public static Cond not(Cond c) {
        if (c.isEmpty()) return Cond.EMPTY;
        return of(w -> w.sql("NOT (").render(c).sql(")"));
    }

    /**
     * 渲染模板：把 {@code {0}}、{@code {1}}…替换为对应参数的渲染结果。
     * 花括号里不是数字的部分原样保留；下标超出参数个数时报错。
     */
    public static void renderTemplate(SqlWriter w, String template, List<? extends Renderable> args) {
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('{', i);
            if (open < 0) {
                w.sql(template.substring(i));
                return;
            }
            int close = template.indexOf('}', open);
            String inside = close < 0 ? "" : template.substring(open + 1, close);
            if (!inside.isEmpty() && inside.chars().allMatch(Character::isDigit)) {
                int index = Integer.parseInt(inside);
                if (index >= args.size()) throw new IllegalArgumentException("Template refers to {" + index + "} but has " + args.size() + " arguments");
                w.sql(template.substring(i, open)).render(args.get(index));
                i = close + 1;
            } else {
                w.sql(template.substring(i, open + 1));
                i = open + 1;
            }
        }
    }
}
