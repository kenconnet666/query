package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.FunctionRenderer;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.JdbcExecutor;
import org.dpdns.zerodep.query.exec.QueryCache;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.type.TypeRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * One database of one dialect. Each dialect package subclasses it ({@code HanaDatabase},
 * {@code PostgresDatabase}); there is no dialect-neutral instance. Thread-safe and meant to be a
 * long-lived singleton per data source.
 */
public abstract class Database {
    private final Dialect dialect;
    private final String name;
    private final Settings settings;
    private final JdbcExecutor executor;

    private record Settings(SourceFilters filters, QueryCache cache, NamingStrategy naming,
                            Map<FunctionDef, FunctionRenderer> functions, Map<Object, Object> attributes) { }

    protected Database(Dialect dialect, ConnectionProvider connections, Consumer<Config> configure) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        Config config = new Config();
        if (configure != null) configure.accept(config);
        this.name = config.name != null ? config.name : dialect.name() + "@" + Integer.toHexString(System.identityHashCode(this));
        Map<Object, Object> attributes = new HashMap<>();
        SourceFilters filters = new SourceFilters(config.filters);
        attributes.put(SourceFilters.class, filters);
        attributes.put(NamingStrategy.class, config.naming);
        this.settings = new Settings(filters, config.cache, config.naming, Map.copyOf(config.functions), Map.copyOf(attributes));
        TypeRegistry types = dialect.types().with(config.types);
        this.executor = new JdbcExecutor(name, Objects.requireNonNull(connections, "connections"), types, config.listeners, config.fetchSize);
    }

    public final Dialect dialect() { return dialect; }

    public final String name() { return name; }

    // ---------- execution ----------

    public <R> List<R> list(Query<R> q) {
        List<Object[]> rows = rows(q, render(q));
        List<R> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) out.add(q.mapRow(row));
        return out;
    }

    /** Exactly one row, otherwise an exception. */
    public <R> R one(Query<R> q) {
        List<R> rows = list(q.limitAtMost(2));
        if (rows.size() != 1) throw new IllegalStateException("Expected exactly one row, got " + (rows.isEmpty() ? "none" : "more than one"));
        return rows.get(0);
    }

    /** The first row, reading at most one. */
    public <R> Optional<R> first(Query<R> q) {
        List<R> rows = list(q.limitAtMost(1));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** Rows read lazily from one cursor; close the stream (try-with-resources) to release the connection. */
    public <R> Stream<R> stream(Query<R> q) {
        return executor.stream(render(q), executor.columns(q.columnTypes())).map(q::mapRow);
    }

    public <R> void forEach(Query<R> q, Consumer<? super R> action) {
        try (Stream<R> rows = stream(q)) {
            rows.forEach(action);
        }
    }

    public long count(Query<?> q) {
        Rendered statement = QueryRenderer.count(writer(), q);
        List<Object[]> rows = executor.fetch(statement, executor.columns(List.of(SqlType.of(Long.class))));
        return ((Number) rows.get(0)[0]).longValue();
    }

    /** Page numbers start at 1; the total comes from a COUNT(*) over the same query. */
    public <R> Page<R> page(Query<R> q, int page, int size) {
        if (page < 1 || size < 1) throw new IllegalArgumentException("page and size start at 1");
        List<R> rows = list(q.limit(size).offset((page - 1) * size));
        return new Page<>(rows, count(q), page, size);
    }

    /** Hand-written SQL with :named parameters, mapped by column label. Collections expand for IN (:list). */
    public NativeQuery sql(String sql) { return new NativeQuery(this, sql); }

    /** The SQL this database would run for the query, with filters and overrides applied. */
    public Rendered render(Query<?> q) { return q.rendered(this, () -> QueryRenderer.statement(writer(), q)); }

    public void clearCache() {
        if (settings.cache() != null) settings.cache().clear();
    }

    // ---------- internals ----------

    JdbcExecutor executor() { return executor; }

    SqlWriter writer() { return new SqlWriter(dialect, settings.functions(), settings.attributes()); }

    private List<Object[]> rows(Query<?> q, Rendered statement) {
        if (q.ttl() == null) return executor.fetch(statement, executor.columns(q.columnTypes()));
        if (settings.cache() == null) {
            throw new IllegalStateException("Query is .cached(...) but database " + name + " has no cache; configure config.cache(QueryCache.inMemory())");
        }
        var key = new QueryCache.Key(name, statement.sql(), statement.parameters());
        boolean[] loaded = {false};
        List<Object[]> rows = settings.cache().get(key, q.ttl(), () -> {
            loaded[0] = true;
            return executor.fetch(statement, executor.columns(q.columnTypes()));
        });
        if (!loaded[0]) executor.cacheHit(statement, rows.size());
        return rows;
    }

    /** Settings of a database instance, given once when it is created. */
    public static final class Config {
        private final List<SourceFilters.Filter> filters = new ArrayList<>();
        private final List<QueryListener> listeners = new ArrayList<>();
        private final Map<FunctionDef, FunctionRenderer> functions = new HashMap<>();
        private TypeRegistry types = TypeRegistry.empty();
        private QueryCache cache;
        private NamingStrategy naming = NamingStrategy.AS_IS;
        private int fetchSize;
        private String name;

        Config() { }

        /**
         * A condition added to every table whose entity is {@code type} or a subtype: to WHERE for the
         * FROM table, to ON for joined tables. A query skips all filters with {@code .unfiltered()}.
         */
        @SuppressWarnings("unchecked")
        public <E> Config filter(Class<E> type, Function<Ref<E>, Cond> condition) {
            filters.add(new SourceFilters.Filter(type, ref -> condition.apply((Ref<E>) ref)));
            return this;
        }

        public Config cache(QueryCache cache) {
            this.cache = cache;
            return this;
        }

        public Config listener(QueryListener listener) {
            listeners.add(listener);
            return this;
        }

        /** Column names for properties without an explicit {@code @Column} name. */
        public Config naming(NamingStrategy naming) {
            this.naming = Objects.requireNonNull(naming);
            return this;
        }

        /** Renders a function differently on this database only. */
        public Config function(FunctionDef function, FunctionRenderer renderer) {
            functions.put(function, renderer);
            return this;
        }

        /** Overrides how a storage type is read and bound on this database. */
        public <S> Config type(Class<S> storageType, TypeRegistry.Reader<S> reader, TypeRegistry.Writer<S> writer) {
            types = types.with(storageType, reader, writer);
            return this;
        }

        public Config fetchSize(int rows) {
            this.fetchSize = rows;
            return this;
        }

        /** Shown in errors, listener events and cache keys; defaults to the dialect name and an id. */
        public Config name(String name) {
            this.name = name;
            return this;
        }
    }
}
