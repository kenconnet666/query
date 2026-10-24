package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 累积一条语句的 SQL 文本和参数。每个节点都把自己渲染进一个写入器；
 * 引号、字面量、分页和函数的写法由方言决定。渲染过程中登记的 CTE 会由 {@link #statement()}
 * 提升为语句开头的同一个 WITH，被依赖的排在前面，因此 WITH 不会出现在派生表内部。
 */
public final class SqlWriter {
    private final Dialect dialect;
    private final Map<FunctionDef, FunctionRenderer> functionOverrides;
    private final Map<Object, Object> attributes;
    private final Aliases aliases;
    private final Map<String, CteDefinition> ctes = new LinkedHashMap<>();
    private final StringBuilder sql = new StringBuilder();
    private final List<Binding> bindings = new ArrayList<>();
    private boolean inlining;
    /** 渲染每个节点前先经过它，可以把节点换成别的写法；null 表示原样渲染。 */
    private UnaryOperator<Renderable> substitution;

    /** 表别名在一条语句内唯一，跨子查询和 CTE 主体也一样，所以关联查询不会出现别名遮蔽。 */
    private static final class Aliases {
        final Map<Object, String> byOwner = new IdentityHashMap<>();
        final Set<String> used = new HashSet<>();
        /** 探查用的写入器记下渲染过程中引用到的数据源；普通写入器为 null。 */
        Set<Object> touched;
    }

    /** 使用方言的默认设置，没有函数覆盖和渲染属性。 */
    public SqlWriter(Dialect dialect) { this(dialect, Map.of(), Map.of()); }

    /**
     * 创建一个写入器。
     *
     * @param functionOverrides 数据库实例对函数渲染的覆盖
     * @param attributes        数据库实例传给渲染过程的设置，用 {@link #attribute} 读取
     */
    public SqlWriter(Dialect dialect, Map<FunctionDef, FunctionRenderer> functionOverrides, Map<Object, Object> attributes) {
        this(dialect, functionOverrides, attributes, new Aliases());
    }

    /** 全部字段的构造；fork 与探查写入器用它共享或新建别名表。 */
    private SqlWriter(Dialect dialect, Map<FunctionDef, FunctionRenderer> functionOverrides, Map<Object, Object> attributes, Aliases aliases) {
        this.dialect = dialect;
        this.functionOverrides = functionOverrides;
        this.attributes = attributes;
        this.aliases = aliases;
    }

    /** 数据源在整条语句中的别名；首选名已被占用时加数字后缀。 */
    public String aliasFor(Object owner, String preferred) {
        if (aliases.touched != null) aliases.touched.add(owner);
        String known = aliases.byOwner.get(owner);
        if (known != null) return known;
        String base = dialect.foldCase(preferred);
        String alias = base;
        for (int i = 2; !aliases.used.add(alias); i++) alias = base + i;
        aliases.byOwner.put(owner, alias);
        return alias;
    }

    /**
     * 探查用的写入器：设置与本写入器相同，别名独立，并记下渲染过程中引用到的每个数据源，见 {@link #touched()}。
     * 渲染器用它先试渲染一遍，找出哪些隐式连接真正被用到。
     */
    public SqlWriter probe() {
        Aliases fresh = new Aliases();
        fresh.touched = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return new SqlWriter(dialect, functionOverrides, attributes, fresh);
    }

    /** 探查写入器记下的数据源；普通写入器返回空集合。 */
    public Set<Object> touched() { return aliases.touched == null ? Set.of() : aliases.touched; }

    /** 当前语句使用的方言。 */
    public Dialect dialect() { return dialect; }

    /** 数据库实例传给渲染过程的设置，例如过滤器或命名策略。 */
    public Object attribute(Object key) { return attributes.get(key); }

    /** 数据库实例对该函数的覆盖渲染；没有则为 null。仅供 {@link FunctionDef} 使用。 */
    FunctionRenderer functionOverride(FunctionDef def) { return functionOverrides.get(def); }

    /** 原样追加 SQL 文本。 */
    public SqlWriter sql(String text) {
        sql.append(text);
        return this;
    }

    /** {@link #attribute} 的键：数据库实例配置的 schema 映射，值是 {@code UnaryOperator<String>}。 */
    public static final Object SCHEMAS = new Object();

    /** 写出 {@code schema.表名}：schema 按数据库实例的映射换成实际的名字；没有 schema 时只写表名。 */
    @SuppressWarnings("unchecked")
    public SqlWriter table(String schema, String table) {
        if (schema != null) {
            UnaryOperator<String> mapping = (UnaryOperator<String>) attributes.get(SCHEMAS);
            identifier(mapping == null ? schema : mapping.apply(schema)).sql(".");
        }
        return identifier(table);
    }

    /** 写出按方言需要加引号的标识符。 */
    public SqlWriter identifier(String name) { return sql(dialect.quote(name)); }

    /** 写出别名，别名应来自 {@link #aliasFor}。 */
    public SqlWriter alias(String alias) { return sql(dialect.alias(alias)); }

    /** 写出一个 {@code ?} 占位符，并按顺序登记要绑定的值及其类型。 */
    public SqlWriter bind(Object value, SqlType<?> type) {
        sql.append('?');
        bindings.add(new Binding(value, type));
        return this;
    }

    /** 把值按方言的字面量形式写入语句，而不是作为参数。 */
    public SqlWriter literal(Object value) {
        dialect.literal(this, value);
        return this;
    }

    /**
     * 常量是否渲染为字面量而不是参数。所有 SELECT 列表都会打开它，因为 HANA 无法推断列表里参数的类型。
     * 带分组和 DISTINCT 的 SELECT 还会在 GROUP BY、HAVING 和 ORDER BY 中打开：
     * HANA、PostgreSQL 和 MySQL 都会拒绝 {@code GROUP BY SUBSTRING(x, ?, ?)}，
     * 因为两处占位符永远不被视为同一个表达式。WHERE 和 ON 仍然使用参数。
     */
    public boolean inlining() { return inlining; }

    /** 设置 {@link #inlining()} 并返回之前的值，方便调用方恢复。 */
    public boolean inlining(boolean on) {
        boolean previous = inlining;
        inlining = on;
        return previous;
    }

    /** 渲染一个节点；设置了替换时先经过替换。 */
    public SqlWriter render(Renderable node) {
        (substitution == null ? node : substitution.apply(node)).render(this);
        return this;
    }

    /**
     * 设置节点替换并返回原来的设置（用完要还原）；渲染器用它把 QUALIFY 条件里的窗口函数换成派生表的列。
     * 替换只看节点本身，被换掉的节点不会再往下渲染。
     */
    public UnaryOperator<Renderable> substitution(UnaryOperator<Renderable> next) {
        UnaryOperator<Renderable> previous = substitution;
        substitution = next;
        return previous;
    }

    /** 依次渲染多个节点，中间用 {@code separator} 分隔。 */
    public SqlWriter join(List<? extends Renderable> nodes, String separator) {
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sql.append(separator);
            render(nodes.get(i));
        }
        return this;
    }

    /** 返回设置相同、缓冲区为空的新写入器，用来单独渲染某一部分；别名表与当前写入器共享。 */
    public SqlWriter fork() {
        SqlWriter f = new SqlWriter(dialect, functionOverrides, attributes, aliases);
        f.substitution = substitution;
        return f;
    }

    /** 追加另一个写入器的文本和参数，并接收它登记的 CTE。 */
    public SqlWriter append(SqlWriter other) {
        sql.append(other.sql);
        bindings.addAll(other.bindings);
        other.ctes.values().forEach(this::cte);
        return this;
    }

    /** 登记一个 CTE，稍后由 {@link #statement()} 提升到语句开头；同名的不同 CTE 会抛出异常。 */
    public void cte(CteDefinition definition) {
        CteDefinition known = ctes.putIfAbsent(definition.name(), definition);
        if (known != null && known != definition) {
            throw new IllegalStateException("Two different CTEs are named '" + definition.name() + "' in one statement");
        }
    }

    /** 当前累积的 SQL 文本，不含提升的 CTE。 */
    public String text() { return sql.toString(); }

    /** 当前累积的参数，按占位符顺序，不含 CTE 主体里的参数。 */
    public List<Binding> bindings() { return List.copyOf(bindings); }

    /** 完整的语句：先是提升的 CTE，再是本写入器的文本。 */
    public Rendered statement() {
        List<CteDefinition> order = new ArrayList<>();
        Map<CteDefinition, SqlWriter> bodies = new IdentityHashMap<>();
        Map<String, CteDefinition> names = new LinkedHashMap<>();
        for (CteDefinition c : ctes.values()) emit(c, order, bodies, names);
        if (order.isEmpty()) return new Rendered(text(), bindings, dialect);
        StringBuilder out = new StringBuilder("WITH ");
        List<Binding> all = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            CteDefinition c = order.get(i);
            SqlWriter body = bodies.get(c);
            if (i > 0) out.append(",\n");
            out.append(dialect.quote(c.name())).append(" AS (\n").append(body.sql).append("\n)");
            all.addAll(body.bindings);
        }
        out.append('\n').append(sql);
        all.addAll(bindings);
        return new Rendered(out.toString(), all, dialect);
    }

    /** 渲染一个 CTE 的主体并递归处理它依赖的 CTE，依赖先于它加入 {@code order}。 */
    private void emit(CteDefinition c, List<CteDefinition> order, Map<CteDefinition, SqlWriter> bodies, Map<String, CteDefinition> names) {
        if (bodies.containsKey(c)) return;
        CteDefinition clash = names.putIfAbsent(c.name(), c);
        if (clash != null && clash != c) throw new IllegalStateException("Two different CTEs are named '" + c.name() + "' in one statement");
        SqlWriter body = fork();
        bodies.put(c, body);
        c.render(body);
        for (CteDefinition dependency : body.ctes.values()) emit(dependency, order, bodies, names);
        order.add(c);
    }

    /** 目前已登记的 CTE 名字。 */
    public Set<String> cteNames() { return ctes.keySet(); }
}
