package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.FunctionRenderer;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.JdbcExecutor;
import org.dpdns.zerodep.query.exec.QueryCache;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;
import org.dpdns.zerodep.query.table.SourceFilters;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.type.TypeRegistry;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.write.Fills;
import org.dpdns.zerodep.query.write.Insert;
import org.dpdns.zerodep.query.write.Write;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 某个方言下的一个数据库。各方言包各自继承它（{@code HanaDatabase}、{@code PostgresDatabase}），
 * 没有与方言无关的实例。线程安全，每个数据源创建一个长期使用的单例即可。
 */
public abstract class Database {
    /** 本数据库的方言。 */
    private final Dialect dialect;

    /** 数据库名，出现在错误、监听事件和缓存键里。 */
    private final String name;

    /** 创建时固定下来的配置。 */
    private final Settings settings;

    /** 执行 SQL 的 JDBC 执行器。 */
    private final JdbcExecutor executor;

    /** 当前线程正在 {@link #capture} 里时，收集执行过的语句。 */
    private final ThreadLocal<List<QueryListener.Event>> captured = new ThreadLocal<>();

    /**
     * 创建后不再变化的配置，从 {@link Config} 复制而来。functions 是本库专属的函数渲染覆盖，
     * attributes 是渲染时通过 SqlWriter 取用的共享对象（全局过滤器、命名策略）。
     */
    private record Settings(SourceFilters filters, QueryCache cache, NamingStrategy naming,
                            Map<FunctionDef, FunctionRenderer> functions, Map<Object, Object> attributes) { }

    /** 由方言子类调用；configure 可为 null，表示全部用默认设置。 */
    protected Database(Dialect dialect, ConnectionProvider connections, Consumer<Config> configure) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        Config config = new Config();
        if (configure != null) configure.accept(config);
        this.name = config.name != null ? config.name : dialect.name() + "@" + Integer.toHexString(System.identityHashCode(this));
        Map<Object, Object> attributes = new HashMap<>();
        SourceFilters filters = new SourceFilters(config.filters);
        attributes.put(SourceFilters.class, filters);
        attributes.put(NamingStrategy.class, config.naming);
        attributes.put(Fills.class, new Fills(config.fills));
        this.settings = new Settings(filters, config.cache, config.naming, Map.copyOf(config.functions), Map.copyOf(attributes));
        TypeRegistry types = dialect.types().with(config.types);
        List<QueryListener> listeners = new ArrayList<>();
        listeners.add(this::record);
        listeners.addAll(config.listeners);
        this.executor = new JdbcExecutor(name, dialect, Objects.requireNonNull(connections, "connections"), types, listeners, config.fetchSize,
            config.queryTimeout);
    }

    /** 本数据库的方言。 */
    public final Dialect dialect() { return dialect; }

    /** 本数据库的名字，见 {@link Config#name}。 */
    public final String name() { return name; }

    // ---------- 执行 ----------

    /** 执行查询，返回全部行。设置了 {@code .cached(...)} 的查询先查结果缓存。 */
    public <R> List<R> list(Query<R> q) {
        return q.mapRows(rows(q, render(q)), this::list);
    }

    /** 恰好一行，没有或多于一行都抛出异常。最多只读取两行。 */
    public <R> R one(Query<R> q) {
        List<R> rows = list(q.limitAtMost(2));
        if (rows.size() != 1) throw new IllegalStateException("Expected exactly one row, got " + (rows.isEmpty() ? "none" : "more than one"));
        return rows.get(0);
    }

    /** 第一行，没有时为空；最多只读取一行。 */
    public <R> Optional<R> first(Query<R> q) {
        List<R> rows = list(q.limitAtMost(1));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 从同一个游标惰性读取各行；用完要关闭流（try-with-resources）以释放连接。 */
    public <R> Stream<R> stream(Query<R> q) {
        if (q.hasChildren()) throw new UnsupportedOperationException("A query with list(...) needs all rows before it can load the children; use list() or page()");
        return executor.stream(render(q), executor.columns(q.columnTypes()), q.timeout()).map(q::mapRow);
    }

    /** 逐行处理查询结果，处理完自动关闭游标。 */
    public <R> void forEach(Query<R> q, Consumer<? super R> action) {
        try (Stream<R> rows = stream(q)) {
            rows.forEach(action);
        }
    }

    /** 查询的总行数，即对同一条查询执行 {@code SELECT COUNT(*)}，忽略 ORDER BY。 */
    public long count(Query<?> q) {
        Rendered statement = QueryRenderer.count(writer(), q);
        List<Object[]> rows = executor.fetch(statement, executor.columns(List.of(SqlType.of(Long.class))), q.timeout());
        return rows.isEmpty() ? 0 : ((Number) rows.get(0)[0]).longValue();  // 空跑时没有行
    }

    /**
     * 运行 work，返回它在当前线程里通过本数据库执行的全部语句（按执行顺序，含缓存命中），便于在测试里断言 SQL 和参数：
     * <pre>{@code
     * List<QueryListener.Event> sqls = db.capture(() -> service.load(request));
     * assertEquals("SELECT ...", sqls.get(0).statement().sql());
     * }</pre>
     * 配合 {@link ConnectionProvider#dryRun()} 可以不连数据库。可以嵌套，内层捕获的语句不再计入外层。
     */
    public List<QueryListener.Event> capture(Runnable work) {
        List<QueryListener.Event> outer = captured.get();
        List<QueryListener.Event> mine = new ArrayList<>();
        captured.set(mine);
        try {
            work.run();
        } finally {
            if (outer == null) captured.remove();
            else captured.set(outer);
        }
        return List.copyOf(mine);
    }

    /** 当前线程在捕获时，记下这条语句。 */
    private void record(QueryListener.Event event) {
        List<QueryListener.Event> sink = captured.get();
        if (sink != null) sink.add(event);
    }

    /** 取第 page 页，每页 size 行，页码从 1 开始；总数来自对同一条查询的 COUNT(*)。 */
    public <R> Page<R> page(Query<R> q, int page, int size) {
        if (page < 1 || size < 1) throw new IllegalArgumentException("page and size start at 1");
        List<R> rows = list(q.limit(size).offset((page - 1) * size));
        return new Page<>(rows, count(q), page, size);
    }

    /** 同上，但总行数由调用方给出（例如翻页时沿用第一页查到的总数），不再执行 COUNT 查询。 */
    public <R> Page<R> page(Query<R> q, int page, int size, long total) {
        if (page < 1 || size < 1) throw new IllegalArgumentException("page and size start at 1");
        if (total < 0) throw new IllegalArgumentException("total must not be negative");
        return new Page<>(list(q.limit(size).offset((page - 1) * size)), total, page, size);
    }

    /** 手写 SQL，用 :name 形式的命名参数，结果按列标签映射。集合参数会展开成 IN (:list)。 */
    public NativeQuery sql(String sql) { return new NativeQuery(this, sql); }

    /** 本数据库会为这条查询执行的 SQL 与参数，已套用全局过滤器和函数覆盖；同一条查询在同一个数据库上的渲染结果会被缓存。 */
    public Rendered render(Query<?> q) { return q.rendered(this, () -> QueryRenderer.statement(writer(), q)); }

    // ---------- 写入 ----------

    /**
     * 执行写语句（{@code insert}、{@code update}、{@code delete}），返回影响的总行数；多行插入按批执行。
     * 语句带期望的行数（{@code expect(n)}、{@code byId(row)}）而实际不符时抛出 {@link UnexpectedRowCountException}。
     * 驱动没有报告批量的行数时（例如 MySQL 的 rewriteBatchedStatements）返回 -1，这时有期望的行数会报错。
     * 写入成功后清空结果缓存，免得读到改动前的结果。
     */
    public long execute(Write write) {
        List<Rendered> statements = render(write);
        if (statements.isEmpty()) return 0;
        long rows = atomically(statements, () -> executor.update(statements, null));
        clearCache();
        Long expected = write.expected();
        if (expected == null || executor.dryRun()) return rows;
        if (rows < 0) {
            throw new IllegalStateException(name + ": the driver did not report how many rows the batch changed, so expect(...) cannot be checked;"
                + " turn off batch rewriting (MySQL rewriteBatchedStatements, PostgreSQL reWriteBatchedInserts)");
        }
        if (rows != expected) throw new UnexpectedRowCountException(name, statements.get(0), expected, rows);
        return rows;
    }

    /**
     * 插入并按行的顺序取回 {@code @Generated} 列生成的值：PostgreSQL 由驱动加上 RETURNING，MySQL 取 AUTO_INCREMENT，
     * HANA 在同一个连接上查 {@code CURRENT_IDENTITY_VALUE()}。多行在一个事务里逐行执行（不按批）。
     */
    @SuppressWarnings("unchecked")
    public <K> List<K> insertKeys(Insert<?> insert) {
        PropertyMeta generated = insert.generated();
        List<Rendered> statements = render(insert);
        if (statements.isEmpty()) return List.of();
        String column = generated.columnName(settings.naming());
        List<Object> keys = atomically(statements, () -> executor.insertKeys(statements, column, generated.type()));
        clearCache();
        return (List<K>) keys;
    }

    /** 多条语句时在一个事务里执行（已经在事务里、或连接来源自己管理事务时直接执行），免得失败时只写了一部分。 */
    private <T> T atomically(List<Rendered> statements, Supplier<T> work) {
        return statements.size() > 1 && executor.canStartTransaction() ? executor.transaction(work) : work.get();
    }

    /** 本数据库会为这条写语句执行的语句与参数，已套用全局过滤器；多行插入每行一条。 */
    public List<Rendered> render(Write write) { return write.render(this::writer); }

    /**
     * 在一个事务里执行 work 并返回它的结果：其中的查询和写语句用同一个连接，正常结束时提交，抛出异常时回滚；
     * 嵌套调用加入外层事务。Spring 项目用 {@code @Transactional}，这里会报错。
     */
    public <T> T transaction(Supplier<T> work) { return executor.transaction(work); }

    /** 同上，没有返回值。 */
    public void transaction(Runnable work) {
        executor.transaction(() -> {
            work.run();
            return null;
        });
    }

    /** 清空查询结果缓存；没有配置缓存时什么也不做。 */
    public void clearCache() {
        if (settings.cache() != null) settings.cache().clear();
    }

    // ---------- 内部 ----------

    /** JDBC 执行器，供 {@link NativeQuery} 等包内类使用。 */
    JdbcExecutor executor() { return executor; }

    /** 新建一个带本库方言、函数覆盖和共享对象的 SQL 写入器。 */
    SqlWriter writer() { return new SqlWriter(dialect, settings.functions(), settings.attributes()); }

    /** 读取查询结果的原始行；设置了 ttl 时经过缓存，命中时通知监听器。 */
    private List<Object[]> rows(Query<?> q, Rendered statement) {
        if (q.ttl() == null) return executor.fetch(statement, executor.columns(q.columnTypes()), q.timeout());
        if (settings.cache() == null) {
            throw new IllegalStateException("Query is .cached(...) but database " + name + " has no cache; configure config.cache(QueryCache.inMemory())");
        }
        var key = new QueryCache.Key(name, statement.sql(), statement.parameters());
        boolean[] loaded = {false};
        List<Object[]> rows = settings.cache().get(key, q.ttl(), () -> {
            loaded[0] = true;
            return executor.fetch(statement, executor.columns(q.columnTypes()), q.timeout());
        });
        if (!loaded[0]) executor.cacheHit(statement, rows.size());
        return rows;
    }

    /** 数据库实例的设置，创建时一次给定。 */
    public static final class Config {
        /** 全局过滤器，按添加顺序生效。 */
        private final List<SourceFilters.Filter> filters = new ArrayList<>();

        /** 写入时自动填充的列。 */
        private final List<Fills.Fill> fills = new ArrayList<>();

        /** 查询监听器。 */
        private final List<QueryListener> listeners = new ArrayList<>();

        /** 本库专属的函数渲染，优先于方言的默认写法。 */
        private final Map<FunctionDef, FunctionRenderer> functions = new HashMap<>();

        /** 本库专属的类型读写覆盖，叠加在方言的类型之上。 */
        private TypeRegistry types = TypeRegistry.empty();

        /** 查询结果缓存，null 表示未配置。 */
        private QueryCache cache;

        /** 未显式指定 {@code @Column} 名字的属性使用的命名策略。 */
        private NamingStrategy naming = NamingStrategy.AS_IS;

        /** JDBC fetchSize，0 表示由驱动决定。 */
        private int fetchSize;

        /** 语句的默认超时，null 表示不限。 */
        private Duration queryTimeout;

        /** 数据库名，null 时取方言名加一个标识。 */
        private String name;

        /** 由 {@link Database} 的构造方法创建，用户通过 configure 回调使用。 */
        Config() { }

        /**
         * 全局过滤器：表接口是 type（或继承了 type）的每张表都加上这个条件，FROM 的表加到 WHERE，JOIN 的表加到 ON。
         * 例如 {@code filter(Client.class, c -> c.client().eq("100"))}；单个查询用 {@code .unfiltered()} 关闭。
         */
        @SuppressWarnings("unchecked")
        public <T> Config filter(Class<T> type, Function<? super T, Cond> condition) {
            filters.add(new SourceFilters.Filter(type, table -> condition.apply((T) table)));
            return this;
        }

        /** 配置查询结果缓存；只有设置了 {@code .cached(...)} 的查询才会使用，例如 {@code QueryCache.inMemory()}。 */
        /**
         * 插入 type（表接口或它继承的接口）的表时自动填充一列，例如创建时间：
         * {@code c.onInsert(Audited.class, a -> a.createdAt(), LocalDateTime::now)}；每条语句取一次值，覆盖行里的值。
         */
        @SuppressWarnings("unchecked")
        public <T, V> Config onInsert(Class<T> type, Function<? super T, Col<V>> column, Supplier<? extends V> value) {
            fills.add(new Fills.Fill(type, table -> column.apply((T) table), value, true));
            return this;
        }

        /** 同上，值是一个表达式，例如 {@code currentTimestamp()}。 */
        @SuppressWarnings("unchecked")
        public <T, V> Config onInsert(Class<T> type, Function<? super T, Col<V>> column, Expr<V> value) {
            fills.add(new Fills.Fill(type, table -> column.apply((T) table), () -> value, true));
            return this;
        }

        /**
         * 更新 type 的表时自动填充一列，例如修改人：{@code c.onUpdate(Audited.class, a -> a.updatedBy(), currentUser::name)}；
         * 按主键更新、插入或更新的更新部分、逻辑删除也会填。
         */
        @SuppressWarnings("unchecked")
        public <T, V> Config onUpdate(Class<T> type, Function<? super T, Col<V>> column, Supplier<? extends V> value) {
            fills.add(new Fills.Fill(type, table -> column.apply((T) table), value, false));
            return this;
        }

        /** 同上，值是一个表达式，例如 {@code currentTimestamp()}。 */
        @SuppressWarnings("unchecked")
        public <T, V> Config onUpdate(Class<T> type, Function<? super T, Col<V>> column, Expr<V> value) {
            fills.add(new Fills.Fill(type, table -> column.apply((T) table), () -> value, false));
            return this;
        }

        public Config cache(QueryCache cache) {
            this.cache = cache;
            return this;
        }

        /**
         * 打印每条执行过的语句（SQL、带类型的参数、行数和耗时），输出到 JDK 的 {@link System.Logger}
         * （名为 {@code org.dpdns.zerodep.query.sql}，级别 INFO）。格式见 {@link SqlPrinter}。
         */
        public Config printSql() { return listener(SqlPrinter.toSystemLogger()); }

        /** 打印每条执行过的语句到 out，例如 {@code log::info} 或 {@code System.out::println}。 */
        public Config printSql(Consumer<String> out) { return listener(new SqlPrinter(out)); }

        /** 添加查询监听器，接收每条语句的执行事件；可以添加多个。 */
        public Config listener(QueryListener listener) {
            listeners.add(listener);
            return this;
        }

        /** 没有显式 {@code @Column} 名字的属性按这个策略取列名。 */
        public Config naming(NamingStrategy naming) {
            this.naming = Objects.requireNonNull(naming);
            return this;
        }

        /** 只在本数据库上用另一种写法渲染某个函数。 */
        public Config function(FunctionDef function, FunctionRenderer renderer) {
            functions.put(function, renderer);
            return this;
        }

        /** 只在本数据库上改变某个存储类型的读取和绑定方式。 */
        public <S> Config type(Class<S> storageType, TypeRegistry.Reader<S> reader, TypeRegistry.Writer<S> writer) {
            types = types.with(storageType, reader, writer);
            return this;
        }

        /** 设置 JDBC 每次从服务器取回的行数；0 表示由驱动决定。 */
        public Config fetchSize(int rows) {
            this.fetchSize = rows;
            return this;
        }

        /**
         * 语句运行超过这个时间就被取消，通过 JDBC 的 {@code setQueryTimeout} 实现（按整秒向上取整）。
         * 查询自己的 {@code .timeout(...)} 和 Spring 事务超时优先于它。
         */
        public Config queryTimeout(Duration timeout) {
            if (timeout != null && (timeout.isNegative() || timeout.isZero())) throw new IllegalArgumentException("queryTimeout must be positive");
            this.queryTimeout = timeout;
            return this;
        }

        /** 数据库名，出现在错误、监听事件和缓存键里；默认是方言名加一个标识。 */
        public Config name(String name) {
            this.name = name;
            return this;
        }
    }
}
