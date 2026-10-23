package org.dpdns.zerodep.query.expr;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一段原生 SQL：模板里的 {@code {0}}、{@code {1}} 依次是参数。参数是表达式或条件时原位渲染；其他值成为绑定参数
 * （SELECT 列表和分组里写成字面量）；集合展开成逗号分隔的多个参数，适合 {@code IN ({1})}。
 * 可以按方言给出另一种写法，渲染时用与当前方言最接近的那个，没有匹配的就用默认模板。
 * {@code sql(...)}、{@code sqlCond(...)} 和 {@code sqlTable(...)} 都由它渲染。
 */
public final class SqlTemplate implements Renderable {
    /** 默认模板。 */
    private final String template;

    /** 各方言专用的模板，按添加顺序。 */
    private final Map<Class<? extends Dialect>, String> variants;

    /** 渲染好的参数，与占位符的编号一一对应。 */
    private final List<Renderable> args;

    /** 全部字段的构造。 */
    private SqlTemplate(String template, Map<Class<? extends Dialect>, String> variants, List<Renderable> args) {
        this.template = template;
        this.variants = variants;
        this.args = args;
    }

    /** 默认模板和参数，参数的写法见类说明。 */
    public static SqlTemplate of(String template, Object... args) {
        if (template == null) throw new IllegalArgumentException("template is null");
        List<Renderable> rendered = new ArrayList<>();
        for (int i = 0; i < args.length; i++) rendered.add(argument(args[i], i));
        return new SqlTemplate(template, Map.of(), List.copyOf(rendered));
    }

    /** 在 dialect 及其子类上改用 template，参数不变；返回新的模板。 */
    public SqlTemplate on(Class<? extends Dialect> dialect, String template) {
        Map<Class<? extends Dialect>, String> copy = new LinkedHashMap<>(variants);
        copy.put(dialect, template);
        return new SqlTemplate(this.template, Map.copyOf(copy), args);
    }

    /** 按当前方言选模板，在占位符处渲染参数。 */
    @Override public void render(SqlWriter w) { Nodes.renderTemplate(w, templateFor(w.dialect()), args); }

    /** 与 dialect 最接近的方言模板：匹配的方言里继承层次最深的那个；都不匹配时是默认模板。 */
    private String templateFor(Dialect dialect) {
        Class<? extends Dialect> best = null;
        for (Class<? extends Dialect> c : variants.keySet()) {
            if (c.isInstance(dialect) && (best == null || best.isAssignableFrom(c))) best = c;
        }
        return best == null ? template : variants.get(best);
    }

    /** 一个参数的渲染方式：表达式和条件原位渲染，集合展开成多个参数，其余值绑定。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Renderable argument(Object arg, int index) {
        if (arg instanceof Renderable r) return r;
        if (arg == null) throw new IllegalArgumentException("Template argument {" + index + "} is null; write NULL in the template instead");
        if (arg instanceof Collection<?> values) {
            if (values.isEmpty()) throw new IllegalArgumentException("Template argument {" + index + "} is an empty collection");
            List<Renderable> each = new ArrayList<>();
            for (Object v : values) each.add(argument(v, index));
            return w -> w.join(each, ", ");
        }
        return new Nodes.Param(SqlType.of(arg.getClass()), arg);
    }
}
