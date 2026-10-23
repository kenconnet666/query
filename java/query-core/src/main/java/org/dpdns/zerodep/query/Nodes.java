package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.CastType;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.List;

/** Built-in expression and condition nodes. Package-private: users build them through the public API. */
final class Nodes {
    private Nodes() { }

    // ---------- expressions ----------

    static final class Param<T> extends Expr<T> {
        private final T value;

        Param(SqlType<T> type, T value) {
            super(type);
            this.value = value;
        }

        /** A literal of the stored value while the writer inlines constants, otherwise a parameter. */
        @Override public void render(SqlWriter w) {
            Object stored = type().toDatabase(value);
            if (w.inlining() && w.dialect().hasLiteral(stored)) w.literal(stored);
            else w.bind(value, type());
        }
    }

    static final class Literal<T> extends Expr<T> {
        private final T value;

        Literal(SqlType<T> type, T value) {
            super(type);
            this.value = value;
        }

        /** The stored value, so a converter applies to literals as it does to parameters. */
        @Override public void render(SqlWriter w) { w.literal(type().toDatabase(value)); }
    }

    static final class Labeled<T> extends Expr<T> {
        final Expr<T> inner;
        private final String label;

        Labeled(Expr<T> inner, String label) {
            super(inner.type());
            if (label == null || label.isBlank()) throw new IllegalArgumentException("A label must not be blank");
            this.inner = inner;
            this.label = label;
        }

        @Override public void render(SqlWriter w) { inner.render(w); }

        @Override public String label() { return label; }
    }

    static final class Call<T> extends Expr<T> {
        private final FunctionDef function;
        private final String name;
        private final List<? extends Expr<?>> args;

        Call(SqlType<T> type, FunctionDef function, String name, List<? extends Expr<?>> args) {
            super(type);
            this.function = function;
            this.name = name;
            this.args = List.copyOf(args);
        }

        @Override public void render(SqlWriter w) {
            if (function != null) function.render(w, args);
            else FunctionDef.call(w, name, args);
        }

        /** 单参数函数（如 UPPER、TRIM）和 COALESCE、NULLIF 沿用第一个参数的列名，cols(...) 里就不必再写 as。 */
        @Override String nameHint() {
            if (args.isEmpty()) return null;
            return args.size() == 1 || name.equals("COALESCE") || name.equals("NULLIF") ? args.get(0).nameHint() : null;
        }
    }

    static final class Template<T> extends Expr<T> {
        private final String template;
        private final List<? extends Expr<?>> args;

        Template(SqlType<T> type, String template, List<? extends Expr<?>> args) {
            super(type);
            this.template = template;
            this.args = List.copyOf(args);
        }

        @Override public void render(SqlWriter w) { renderTemplate(w, template, args); }
    }

    static final class Arithmetic<T> extends Expr<T> {
        private final String operator;
        private final List<? extends Expr<?>> operands;

        Arithmetic(SqlType<T> type, String operator, List<? extends Expr<?>> operands) {
            super(type);
            this.operator = operator;
            this.operands = List.copyOf(operands);
        }

        @Override public void render(SqlWriter w) { w.sql("(").join(operands, " " + operator + " ").sql(")"); }
    }

    /** Unary minus; the operand in parentheses, so a negative literal never turns into a comment. */
    static final class Negated<T> extends Expr<T> {
        private final Expr<T> operand;

        Negated(Expr<T> operand) {
            super(operand.type());
            this.operand = operand;
        }

        @Override public void render(SqlWriter w) { w.sql("-(").render(operand).sql(")"); }

        /** 取负沿用原列名。 */
        @Override String nameHint() { return operand.nameHint(); }
    }

    static final class Cast<T> extends Expr<T> {
        private final Expr<?> inner;
        private final CastType<T> target;

        Cast(Expr<?> inner, CastType<T> target) {
            super(SqlType.of(target.javaType()));
            this.inner = inner;
            this.target = target;
        }

        @Override public void render(SqlWriter w) {
            w.sql("CAST(").render(inner).sql(" AS ").sql(w.dialect().castType(target)).sql(")");
        }

        /** 类型转换沿用原列名。 */
        @Override String nameHint() { return inner.nameHint(); }
    }

    static final class Scalar<T> extends Expr<T> {
        private final Query<T> query;

        Scalar(Query<T> query) {
            super(query.singleColumnType());
            this.query = query;
        }

        @Override public void render(SqlWriter w) {
            w.sql("(");
            QueryRenderer.subquery(w, query);
            w.sql(")");
        }
    }

    static final class Raw<T> extends Expr<T> {
        private final String sql;

        Raw(SqlType<T> type, String sql) {
            super(type);
            this.sql = sql;
        }

        @Override public void render(SqlWriter w) { w.sql(sql); }
    }

    static <T> Expr<T> call(SqlType<T> type, String name, List<? extends Expr<?>> args) { return new Call<>(type, null, name, args); }

    static <T> Expr<T> call(SqlType<T> type, FunctionDef function, List<? extends Expr<?>> args) {
        return new Call<>(type, function, function.name(), args);
    }

    // ---------- conditions ----------

    private static final class Of extends Cond {
        private final java.util.function.Consumer<SqlWriter> body;

        Of(java.util.function.Consumer<SqlWriter> body) { this.body = body; }

        @Override public void render(SqlWriter w) { body.accept(w); }
    }

    static Cond of(java.util.function.Consumer<SqlWriter> body) { return new Of(body); }

    static Cond compare(Expr<?> left, String operator, Expr<?> right) {
        return of(w -> w.render(left).sql(" " + operator + " ").render(right));
    }

    static Cond between(Expr<?> e, Expr<?> low, Expr<?> high) {
        return of(w -> w.render(e).sql(" BETWEEN ").render(low).sql(" AND ").render(high));
    }

    static Cond postfix(Expr<?> e, String suffix) { return of(w -> w.render(e).sql(suffix)); }

    static final Cond FALSE = of(w -> w.sql("1 = 0"));
    static final Cond TRUE = of(w -> w.sql("1 = 1"));

    static <T> Cond in(Expr<T> e, List<Expr<T>> values, boolean negate) {
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

    static Cond inQuery(Expr<?> e, Query<?> query, boolean negate) {
        return of(w -> {
            w.render(e).sql(negate ? " NOT IN (" : " IN (");
            QueryRenderer.subquery(w, query);
            w.sql(")");
        });
    }

    static Cond exists(Query<?> query, boolean negate) {
        return of(w -> {
            w.sql(negate ? "NOT EXISTS (" : "EXISTS (");
            QueryRenderer.subquery(w, query);
            w.sql(")");
        });
    }

    static Cond template(String template, List<? extends Expr<?>> args) {
        List<? extends Expr<?>> copy = List.copyOf(args);
        return of(w -> renderTemplate(w, template, copy));
    }

    static Cond all(List<Cond> conds) {
        List<Cond> kept = new ArrayList<>();
        for (Cond c : conds) {
            if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
            if (!c.isEmpty()) kept.add(c);
        }
        if (kept.isEmpty()) return Cond.EMPTY;
        if (kept.size() == 1) return kept.get(0);
        return of(w -> w.join(kept, " AND "));
    }

    static Cond any(List<Cond> conds) {
        List<Cond> kept = new ArrayList<>();
        for (Cond c : conds) {
            if (c == null) throw new IllegalArgumentException("A condition is null; use Cond.EMPTY for no condition");
            if (!c.isEmpty()) kept.add(c);
        }
        if (kept.isEmpty()) return Cond.EMPTY;
        if (kept.size() == 1) return kept.get(0);
        // Each operand in parentheses, so an AND inside an OR keeps its meaning.
        return of(w -> {
            w.sql("(");
            for (int i = 0; i < kept.size(); i++) {
                if (i > 0) w.sql(" OR ");
                w.sql("(").render(kept.get(i)).sql(")");
            }
            w.sql(")");
        });
    }

    static Cond not(Cond c) {
        if (c.isEmpty()) return Cond.EMPTY;
        return of(w -> w.sql("NOT (").render(c).sql(")"));
    }

    /** {0}, {1}, ... in the template are replaced by the rendered arguments. */
    static void renderTemplate(SqlWriter w, String template, List<? extends Renderable> args) {
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
