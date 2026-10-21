package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.type.SqlType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates SQL text and parameters for one statement. Every node renders itself into a writer;
 * the dialect decides quoting, literals, paging and function rendering. CTEs registered while
 * rendering are hoisted into one leading WITH by {@link #statement()}, dependencies first, so no
 * WITH ever appears inside a derived table.
 */
public final class SqlWriter {
    private final Dialect dialect;
    private final Map<FunctionDef, FunctionRenderer> functionOverrides;
    private final Map<Object, Object> attributes;
    private final Aliases aliases;
    private final Map<String, CteDefinition> ctes = new LinkedHashMap<>();
    private final StringBuilder sql = new StringBuilder();
    private final List<Binding> bindings = new ArrayList<>();

    /** Table aliases are unique per statement, across subqueries and CTE bodies, so correlation never shadows. */
    private static final class Aliases {
        final Map<Object, String> byOwner = new IdentityHashMap<>();
        final Set<String> used = new HashSet<>();
    }

    public SqlWriter(Dialect dialect) { this(dialect, Map.of(), Map.of()); }

    public SqlWriter(Dialect dialect, Map<FunctionDef, FunctionRenderer> functionOverrides, Map<Object, Object> attributes) {
        this(dialect, functionOverrides, attributes, new Aliases());
    }

    private SqlWriter(Dialect dialect, Map<FunctionDef, FunctionRenderer> functionOverrides, Map<Object, Object> attributes, Aliases aliases) {
        this.dialect = dialect;
        this.functionOverrides = functionOverrides;
        this.attributes = attributes;
        this.aliases = aliases;
    }

    /** The statement-wide alias of a source; the preferred name gets a numeric suffix when taken. */
    public String aliasFor(Object owner, String preferred) {
        String known = aliases.byOwner.get(owner);
        if (known != null) return known;
        String base = dialect.foldCase(preferred);
        String alias = base;
        for (int i = 2; !aliases.used.add(alias); i++) alias = base + i;
        aliases.byOwner.put(owner, alias);
        return alias;
    }

    public Dialect dialect() { return dialect; }

    /** Settings the database instance passes to rendering, such as filters or the naming strategy. */
    public Object attribute(Object key) { return attributes.get(key); }

    FunctionRenderer functionOverride(FunctionDef def) { return functionOverrides.get(def); }

    public SqlWriter sql(String text) {
        sql.append(text);
        return this;
    }

    public SqlWriter identifier(String name) { return sql(dialect.quote(name)); }

    public SqlWriter alias(String alias) { return sql(dialect.alias(alias)); }

    public SqlWriter bind(Object value, SqlType<?> type) {
        sql.append('?');
        bindings.add(new Binding(value, type));
        return this;
    }

    public SqlWriter literal(Object value) {
        dialect.literal(this, value);
        return this;
    }

    public SqlWriter render(Renderable node) {
        node.render(this);
        return this;
    }

    public SqlWriter join(List<? extends Renderable> nodes, String separator) {
        for (int i = 0; i < nodes.size(); i++) {
            if (i > 0) sql.append(separator);
            nodes.get(i).render(this);
        }
        return this;
    }

    /** A writer with the same settings and an empty buffer, for rendering a part separately. */
    public SqlWriter fork() { return new SqlWriter(dialect, functionOverrides, attributes, aliases); }

    /** Appends another writer's text and parameters, and adopts the CTEs it registered. */
    public SqlWriter append(SqlWriter other) {
        sql.append(other.sql);
        bindings.addAll(other.bindings);
        other.ctes.values().forEach(this::cte);
        return this;
    }

    public void cte(CteDefinition definition) {
        CteDefinition known = ctes.putIfAbsent(definition.name(), definition);
        if (known != null && known != definition) {
            throw new IllegalStateException("Two different CTEs are named '" + definition.name() + "' in one statement");
        }
    }

    public String text() { return sql.toString(); }

    public List<Binding> bindings() { return List.copyOf(bindings); }

    /** The finished statement: hoisted CTEs, then this writer's text. */
    public Rendered statement() {
        List<CteDefinition> order = new ArrayList<>();
        Map<CteDefinition, SqlWriter> bodies = new IdentityHashMap<>();
        Map<String, CteDefinition> names = new LinkedHashMap<>();
        for (CteDefinition c : ctes.values()) emit(c, order, bodies, names);
        if (order.isEmpty()) return new Rendered(text(), bindings);
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
        return new Rendered(out.toString(), all);
    }

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

    /** The CTEs registered so far, by name. */
    public Set<String> cteNames() { return ctes.keySet(); }
}
