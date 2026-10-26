package org.dpdns.zerodep.query.exec;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.ddl.Migration;
import org.dpdns.zerodep.query.ddl.Migrator;
import org.dpdns.zerodep.query.ddl.SchemaSyntax;
import org.dpdns.zerodep.query.ddl.SchemaTools;
import org.dpdns.zerodep.query.dialect.Binding;
import org.dpdns.zerodep.query.dialect.Dialect;
import org.dpdns.zerodep.query.dialect.FunctionDef;
import org.dpdns.zerodep.query.dialect.FunctionRenderer;
import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.JdbcExecutor;
import org.dpdns.zerodep.query.exec.QueryCache;
import org.dpdns.zerodep.query.exec.QueryListener;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.scope.From1;
import org.dpdns.zerodep.query.select.QueryRenderer;
import org.dpdns.zerodep.query.select.Selection;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.ShardRule;
import org.dpdns.zerodep.query.table.Sharding;
import org.dpdns.zerodep.query.table.SourceFilters;
import org.dpdns.zerodep.query.table.column.Col;
import org.dpdns.zerodep.query.type.SqlType;
import org.dpdns.zerodep.query.type.TypeRegistry;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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

    /** 当前线程上的追踪范围；不在 {@link #tracking} 里时为 null。 */
    private final ThreadLocal<Tracker> tracker = new ThreadLocal<>();

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
        attributes.put(Fills.class, new Fills(config.fills, config.generators));
        attributes.put(Sharding.class, config.shards.isEmpty() ? Sharding.NONE : new Sharding(config.shards));
        attributes.put(SqlWriter.WARNED, java.util.concurrent.ConcurrentHashMap.newKeySet());
        Map<String, String> schemas = Map.copyOf(config.schemas);
        UnaryOperator<String> schemaMapping = config.schemaMapping;
        attributes.put(SqlWriter.SCHEMAS, (UnaryOperator<String>) schema -> schemas.getOrDefault(schema, schemaMapping.apply(schema)));
        this.settings = new Settings(filters, config.cache, config.naming, Map.copyOf(config.functions), Map.copyOf(attributes));
        TypeRegistry types = dialect.types().with(config.types);
        List<QueryListener> listeners = new ArrayList<>();
        listeners.add(this::record);
        listeners.addAll(config.listeners);
        this.executor = new JdbcExecutor(name, dialect, Objects.requireNonNull(connections, "connections"), types, listeners, config.interceptors,
            config.fetchSize, config.queryTimeout);
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

    /** 恰好一行：没有时抛 {@link EmptyResultException}，多于一行时抛 {@link TooManyRowsException}。最多只读取两行。 */
    public <R> R one(Query<R> q) {
        List<R> rows = list(q.limitAtMost(2));
        if (rows.isEmpty()) throw new EmptyResultException("Expected exactly one row, got none");
        if (rows.size() > 1) throw new TooManyRowsException("Expected exactly one row, got more than one");
        return rows.get(0);
    }

    /** 零行或一行：没有时为空，多于一行抛出 {@link TooManyRowsException}。最多只读取两行。 */
    public <R> Optional<R> optional(Query<R> q) {
        List<R> rows = list(q.limitAtMost(2));
        if (rows.size() > 1) throw new TooManyRowsException("Expected at most one row, got more than one");
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 第一行，没有时为空；最多只读取一行。 */
    public <R> Optional<R> first(Query<R> q) {
        List<R> rows = list(q.limitAtMost(1));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    /** 从同一个游标惰性读取各行；用完要关闭流（try-with-resources）以释放连接。 */
    public <R> Stream<R> stream(Query<R> q) {
        if (q.hasChildren()) throw new UnsupportedOperationException("A query with list(...) needs all rows before it can load the children; use list() or page()");
        return executor.stream(executor.intercept(render(q)), executor.columns(q.columnTypes()), q.timeout()).map(q::mapRow);
    }

    /** 逐行处理查询结果，处理完自动关闭游标。 */
    public <R> void forEach(Query<R> q, Consumer<? super R> action) {
        try (Stream<R> rows = stream(q)) {
            rows.forEach(action);
        }
    }

    /** 查询是否至少有一行：{@code SELECT CASE WHEN EXISTS (查询) THEN 1 ELSE 0 END}，不读出任何行。 */
    public boolean exists(Query<?> q) {
        Rendered statement = executor.intercept(QueryRenderer.exists(writer(), q));
        List<Object[]> rows = executor.fetch(statement, executor.columns(List.of(SqlType.of(Integer.class))), q.timeout());
        return !rows.isEmpty() && ((Number) rows.get(0)[0]).intValue() == 1;  // 空跑时没有行
    }

    /** 查询的总行数，即对同一条查询执行 {@code SELECT COUNT(*)}，忽略 ORDER BY。 */
    public long count(Query<?> q) {
        Rendered statement = executor.intercept(QueryRenderer.count(writer(), q));
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

    /**
     * 取第 page 页，每页 size 行，页码从 1 开始；总数来自对同一条查询的 COUNT(*)。页码超出时 rows 为空，前端据此退到最后一页。
     * 不先数行数再决定查不查这一页：空跑（{@link ConnectionProvider#dryRun()}）时总数是 0，那样 {@link #capture} 就看不到这一页的查询了。
     */
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

    /**
     * 表格查询（0016）：按请求依次做表头筛选（{@code filterBy}、{@code searchBy}）、排序（{@code sortBy}）和分页；请求带了已知的总数时不再 COUNT。
     * 筛选和排序只认结果列，所以请求里的列名不会碰到结果以外的列；用了没有的列、给文字以外的列做关键字筛选时报
     * {@link InvalidRequestException}。返回的 {@link Page#toWire()} 交给前端。
     */
    public <R> Page<R> table(Query<R> q, TableRequest request) {
        Query<R> shaped;
        try {
            shaped = q.filterBy(request.filter()).searchBy(request.search()).conditionBy(request.condition()).sortBy(request.sort());
        } catch (IllegalArgumentException e) {
            // 列名、排序串都来自前端，这里的参数错误就是请求写错了
            throw e instanceof InvalidRequestException ? e : new InvalidRequestException(e.getMessage(), e);
        }
        return request.total() == null
            ? page(shaped, request.page(), request.pageSize())
            : page(shaped, request.page(), request.pageSize(), request.total());
    }

    /**
     * 树形数据的服务端搜索：命中 hits 的行加上它们的全部祖先，每行按 select 取出，交给 {@link Trees#build} 组装成从根开始的树
     * （前端懒加载的树要在服务端搜索时用）。key、parent 是行的键列和父键列。
     * PostgreSQL、MySQL（PolarDB）、SQLite 写成一条递归 CTE，用 UNION 去重：命中的行共用的祖先只走一遍，父子成环也会停；
     * 没有递归 CTE 的库（HANA）一层一层按键查只有键的两列（{@link Trees#withAncestors}），最后按键分批取 select 的列。行的顺序不保证。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <E extends Entity, R> List<R> withAncestors(Class<E> table, Function<E, ? extends Expr<?>> key, Function<E, ? extends Expr<?>> parent,
                                                        Consumer<E> hits, Function<E, ? extends Selection<R>> select) {
        Function<From1<E>, Query<Trees.Link>> links = scope -> scope.select(e -> Sql.cols(key.apply(e).as("key"), parent.apply(e).as("parent"))).into(Trees.Link.class);
        if (dialect().supportsRecursiveCte()) {
            Cte<Trees.Link> up = Cte.recursive("ancestors", links.apply(Sql.from(table).where(e -> hits.accept(e))),
                self -> Sql.from(self).join(table, (t, e) -> ((Expr) key.apply(e)).eq(t.col(Trees.Link::parent)))
                    .select((t, e) -> Sql.cols(key.apply(e).as("key"), parent.apply(e).as("parent"))).into(Trees.Link.class),
                dialect().getClass(), true);
            return list(Sql.from(table).where(e -> ((Expr) key.apply(e)).in(Sql.from(up).select(t -> t.col(Trees.Link::key)))).select(select::apply));
        }
        List<Trees.Link> all = Trees.withAncestors(list(links.apply(Sql.from(table).where(e -> hits.accept(e)))), Trees.Link::key, Trees.Link::parent,
            ids -> list(links.apply(Sql.from(table).where(e -> ((Expr) key.apply(e)).in(ids)))));
        List<Object> keys = all.stream().map(Trees.Link::key).toList();
        List<R> rows = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += Trees.BATCH) {
            List<Object> batch = keys.subList(i, Math.min(i + Trees.BATCH, keys.size()));
            rows.addAll(list(Sql.from(table).where(e -> ((Expr) key.apply(e)).in(batch)).select(select::apply)));
        }
        return rows;
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
        List<Rendered> statements = render(write).stream().map(executor::intercept).toList();
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
        List<Rendered> statements = render(insert).stream().map(executor::intercept).toList();
        if (statements.isEmpty()) return List.of();
        String column = generated.columnName(settings.naming());
        List<Object> keys = atomically(statements, () -> executor.insertKeys(statements, column, generated.type()));
        clearCache();
        return (List<K>) keys;
    }

    /**
     * 插入并返回插入的行，{@code @Generated} 的属性换成数据库生成的值（自增主键），按行的顺序：
     * <pre>{@code
     * WorkItem saved = db.<WorkItem>insertReturning(insertInto(WorkItemTable.class).values(draft)).get(0);
     * }</pre>
     * record 按规范构造器重建一份；普通类直接设属性。行类没有这个属性时报错（只要编号用 {@link #insertKeys}）。
     */
    public <R> List<R> insertReturning(Insert<?> insert) {
        return insert.withGenerated(insertKeys(insert));
    }

    /**
     * 执行会返回行的写语句（例如 PostgreSQL 的 {@code ... RETURNING}），把各条语句返回的行按列标签映射成 shape 并依次连起来；
     * 供方言的数据库实例使用。多条语句在一个事务里执行，执行后清空结果缓存。
     */
    protected <R> List<R> writeReturning(List<Rendered> rendered, Class<R> shape) {
        List<Rendered> statements = rendered.stream().map(executor::intercept).toList();
        if (statements.isEmpty()) return List.of();
        List<R> rows = atomically(statements, () -> {
            List<R> out = new ArrayList<>();
            for (Rendered s : statements) out.addAll(executor.query(s, rs -> NativeQuery.read(rs, shape, executor.types()), List::size, null));
            return out;
        });
        clearCache();
        return rows;
    }

    /** 本库未显式指定列名的属性所用的命名策略。 */
    protected NamingStrategy naming() { return settings.naming(); }

    /** 多条语句时在一个事务里执行（已经在事务里、或连接来源自己管理事务时直接执行），免得失败时只写了一部分。 */
    private <T> T atomically(List<Rendered> statements, Supplier<T> work) {
        return statements.size() > 1 ? atomically(work) : work.get();
    }

    /** 在一个事务里执行 work；已经在事务里时加入它，连接来源自己管理事务又不能开启事务（只给了 DataSource 的 Spring 适配器）时直接执行。 */
    <T> T atomically(Supplier<T> work) { return executor.canStartTransaction() ? executor.transaction(work) : work.get(); }

    /** 本数据库会为这条写语句执行的语句与参数，已套用全局过滤器；多行插入每行一条。 */
    public List<Rendered> render(Write write) { return write.render(this::writer); }

    /**
     * 在一个事务里执行 work 并返回它的结果：其中的查询和写语句用同一个连接，正常结束时提交，抛出异常时回滚；
     * 嵌套调用加入外层事务。Spring 项目里由事务管理器执行（{@code SpringConnections.of(dataSource, transactionManager)}，
     * 已有 {@code @Transactional} 时加入它）；只给了 DataSource 时报错，事务交给 {@code @Transactional}。
     */
    public <T> T transaction(Supplier<T> work) { return executor.transaction(work); }

    /** 同上，没有返回值。 */
    public void transaction(Runnable work) {
        executor.transaction(() -> {
            work.run();
            return null;
        });
    }

    /**
     * 在一个追踪范围里执行 work：用 {@link Tracker} 读到并登记的行，范围正常结束时把变了的按主键写回
     * （{@code update(表).byId(行).diffFrom(快照)}，同一个事务），抛出异常时什么也不写。范围绑定在当前线程上，
     * 嵌套调用加入外层，由最外层写回。写回成功后行的版本属性变成库里的新值；外层事务之后回滚时，已写回的版本不会撤销。
     * <pre>{@code db.tracking(t -> { DocBean d = t.one(Doc.class, q); d.setTitle("x"); });}</pre>
     */
    public void tracking(Consumer<Tracker> work) {
        tracked(t -> {
            work.accept(t);
            return null;
        });
    }

    /** 同 {@link #tracking}，返回 work 的结果（在写回之后）：{@code DocBean d = db.tracked(t -> { ...; return d; })}。 */
    public <T> T tracked(Function<Tracker, T> work) {
        Tracker outer = tracker.get();
        if (outer != null) return work.apply(outer);
        Tracker t = new Tracker(this);
        tracker.set(t);
        try {
            T result = work.apply(t);
            t.flush();
            return result;
        } finally {
            tracker.remove();
        }
    }


    // ---------- 装回关联数据 ----------

    /**
     * 按键把另一个查询的结果装回已经读出的行（一对一）：收集各行的键（去掉 null 和重复），每批至多 1000 个交给 load
     * 得到查询，执行后按 keyOf 对应回去，用 set 写进行里；没有对应的行写 null。适合不便声明成关系的数据，
     * 例如另一个库、另一个服务的表：
     * <pre>{@code
     * db.attach(orders, OrderVO::getCustomerId,
     *     ids -> from(Customer.class).where(c -> c.id().in(ids)).selectAs(CustomerVO.class),
     *     CustomerVO::getId, OrderVO::setCustomer);
     * }</pre>
     * 同一个键对应多行时抛出 {@link TooManyRowsException}。
     */
    public <R, K, V> void attach(List<R> rows, Function<? super R, K> key, Function<List<K>, Query<V>> load,
                                 Function<? super V, K> keyOf, BiConsumer<? super R, ? super V> set) {
        Map<K, V> found = new HashMap<>();
        for (V v : loadAll(rows, key, load)) {
            if (found.put(keyOf.apply(v), v) != null) throw new TooManyRowsException("attach(...) found more than one row for key " + keyOf.apply(v));
        }
        for (R r : rows) {
            K k = key.apply(r);
            set.accept(r, k == null ? null : found.get(k));
        }
    }

    /** 同 {@link #attach}，但一个键对应多行（一对多），按查询返回的顺序装成列表；没有对应的行写空列表。 */
    public <R, K, V> void attachMany(List<R> rows, Function<? super R, K> key, Function<List<K>, Query<V>> load,
                                     Function<? super V, K> keyOf, BiConsumer<? super R, ? super List<V>> set) {
        Map<K, List<V>> found = new HashMap<>();
        for (V v : loadAll(rows, key, load)) found.computeIfAbsent(keyOf.apply(v), k -> new ArrayList<>()).add(v);
        for (R r : rows) {
            K k = key.apply(r);
            set.accept(r, k == null ? new ArrayList<>() : found.getOrDefault(k, new ArrayList<>()));
        }
    }

    /** 每批至多这么多个键，免得 IN 列表和参数个数过大。 */
    private static final int ATTACH_BATCH = 1000;

    /** 去重后的非空键分批交给 load，返回全部结果行。 */
    private <R, K, V> List<V> loadAll(List<R> rows, Function<? super R, K> key, Function<List<K>, Query<V>> load) {
        List<K> keys = rows.stream().map(key).filter(Objects::nonNull).distinct().toList();
        List<V> out = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += ATTACH_BATCH) out.addAll(list(load.apply(keys.subList(from, Math.min(keys.size(), from + ATTACH_BATCH)))));
        return out;
    }

    // ---------- 结构变更 ----------

    /**
     * 结构变更：比较表接口的声明与实库并生成 DDL，例如 {@code db.schema().plan(Note.class).apply()}；
     * 见 {@link SchemaTools}。方言的写法来自 {@link Dialect#schemaSyntax()}。
     */
    @SuppressWarnings("unchecked")
    public SchemaTools schema() {
        SchemaSyntax syntax = dialect.schemaSyntax();
        UnaryOperator<String> schemas = (UnaryOperator<String>) settings.attributes().get(SqlWriter.SCHEMAS);
        return new SchemaTools(syntax, settings.naming(), schemas, (Sharding) settings.attributes().get(Sharding.class), this::catalogRows,
            statements -> runStatements(statements, syntax.transactionalDdl()));
    }

    /**
     * 版本化迁移的执行器：{@code db.migrator(List.of(new V1(), new V2())).migrateToLatest()}，见 {@link Migrator}。
     * 适合作为部署前的一次性任务运行，而不是在每个应用副本启动时运行。
     */
    public Migrator migrator(List<Migration> migrations) {
        Database db = this;
        return new Migrator(migrations, dialect, dialect.schemaSyntax(), new Migrator.Backend() {
            @Override public List<Map<String, Object>> query(String sql, Object... parameters) { return catalogRows(sql, parameters); }

            @Override public long update(String sql, Object... parameters) { return updateRows(sql, parameters); }

            @Override public void run(List<String> statements) { runStatements(statements, false); }

            @Override public List<Rendered> render(Write write) { return db.render(write); }

            @Override public void execute(Write write) { db.execute(write); }

            @Override public <T> T transaction(Supplier<T> work) { return db.transaction(work); }
        });
    }

    /** 带 {@code ?} 参数的手写写语句，返回影响的行数。 */
    long updateRows(String sql, Object... parameters) {
        return executor.update(List.of(executor.intercept(new Rendered(sql, bindings(parameters), dialect))), null);
    }

    /** 参数按值的类绑定。 */
    private static List<Binding> bindings(Object[] parameters) {
        List<Binding> out = new ArrayList<>();
        for (Object p : parameters) out.add(new Binding(p, SqlType.of(p.getClass())));
        return out;
    }

    /** 执行手写的语句（DDL 等），一条一次；transactional 时在一个事务里执行。执行后清空结果缓存。 */
    void runStatements(List<String> statements, boolean transactional) {
        List<Rendered> rendered = statements.stream().map(s -> executor.intercept(new Rendered(s, List.of(), dialect))).toList();
        Runnable run = () -> {
            for (Rendered r : rendered) executor.update(List.of(r), null);
        };
        if (transactional && executor.canStartTransaction()) transaction(run);
        else run.run();
        clearCache();
    }

    /** 系统目录查询：带 {@code ?} 参数的 SELECT，每行是小写列标签到值的映射。 */
    List<Map<String, Object>> catalogRows(String sql, Object... parameters) {
        Rendered statement = executor.intercept(new Rendered(sql, bindings(parameters), dialect));
        return executor.query(statement, rs -> {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT), rs.getObject(i));
                rows.add(row);
            }
            return rows;
        }, List::size, null);
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

    /** 读取查询结果的原始行；设置了 ttl 时经过缓存（以执行前钩子改写后的语句作键），命中时通知监听器。 */
    private List<Object[]> rows(Query<?> q, Rendered rendered) {
        Rendered statement = executor.intercept(rendered);
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

        /** 执行前的钩子，按添加顺序调用。 */
        private final List<StatementInterceptor> interceptors = new ArrayList<>();

        /** 插入时逐行生成值的列。 */
        private final List<Fills.Generator> generators = new ArrayList<>();

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

        /** 按名字替换的 schema。 */
        private final Map<String, String> schemas = new HashMap<>();

        /** 同库分表的表和规则。 */
        private final Map<Class<?>, ShardRule> shards = new HashMap<>();

        /** 其余 schema 的替换规则，默认不变。 */
        private UnaryOperator<String> schemaMapping = UnaryOperator.identity();

        /** 由 {@link Database} 的构造方法创建，用户通过 configure 回调使用。 */
        Config() { }

        /**
         * 全局过滤器：表接口是 type（或继承了 type）的每张表都加上这个条件，FROM 的表加到 WHERE，JOIN 的表加到 ON。
         * 例如 {@code filter(Client.class, c -> c.client().eq("100"))}，条件一行写一个；单个查询用 {@code .unfiltered()} 关闭。
         */
        @SuppressWarnings("unchecked")
        public <T> Config filter(Class<T> type, Consumer<? super T> condition) {
            filters.add(new SourceFilters.Filter(null, type, table -> condition.accept((T) table)));
            return this;
        }

        /**
         * 具名的全局过滤器，其余同上；单个查询或写语句可以用 {@code .unfiltered(name)} 只关闭它，例如
         * {@code filter("tenant", Client.class, c -> c.client().eq(tenant()))} 后，{@code from(...).unfiltered("tenant")} 跨租户查询，
         * 逻辑删除的条件仍然生效。
         */
        @SuppressWarnings("unchecked")
        public <T> Config filter(String name, Class<T> type, Consumer<? super T> condition) {
            Objects.requireNonNull(name, "name");
            if (filters.stream().anyMatch(f -> name.equals(f.name()))) throw new IllegalArgumentException("A filter named " + name + " is already configured");
            filters.add(new SourceFilters.Filter(name, type, table -> condition.accept((T) table)));
            return this;
        }

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

        /**
         * 表接口里写的 schema 换成实际的名字，例如 SAP 各环境的 schema 不同：{@code schema("ERP", "SAPHANADB")}。
         * 查询、写语句和关系里的表都换；原生 SQL 文本不换。可以多次调用。
         */
        public Config schema(String declared, String actual) {
            schemas.put(Objects.requireNonNull(declared, "declared"), Objects.requireNonNull(actual, "actual"));
            return this;
        }

        /** 同上，按规则替换没有用 {@link #schema(String, String)} 单独指定的 schema，例如 {@code schema(s -> s + "_TEST")}。 */
        public Config schema(UnaryOperator<String> mapping) {
            this.schemaMapping = Objects.requireNonNull(mapping, "mapping");
            return this;
        }

        /**
         * 把 table 分成同库的多张物理表，例如按月：{@code shard(Event.class, ShardRule.monthly("createdOn", YearMonth.of(2025, 1), YearMonth.of(2026, 12)))}。
         * 之后查询按 WHERE 里分表键的条件选表、写成 UNION ALL，写入按行或按条件写到对应的表；推不出来时报错，
         * 除非语句写了 {@code allShards()} 或 {@code onShards(...)}。{@code db.schema()} 按物理表建表和比较。
         */
        public Config shard(Class<? extends Entity> table, ShardRule rule) {
            shards.put(Objects.requireNonNull(table, "table"), Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /** 配置查询结果缓存；只有设置了 {@code .cached(...)} 的查询才会使用，例如 {@code QueryCache.inMemory()}。 */
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

        /** 添加执行前钩子，见 {@link StatementInterceptor}；可以添加多个，按添加顺序调用。 */
        public Config intercept(StatementInterceptor interceptor) {
            interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
            return this;
        }

        /**
         * 插入时逐行生成一列的值，只填行里为 null（或行里没有这个属性）的行，例如雪花主键：
         * {@code generate(Keyed.class, k -> k.id(), ids::next)}。按表接口的父类型或接口匹配；生成的值不写回行对象，
         * 之后还要用这个值时在代码里先生成。
         */
        @SuppressWarnings("unchecked")
        public <T, V> Config generate(Class<T> type, Function<? super T, Col<V>> column, Supplier<? extends V> value) {
            generators.add(new Fills.Generator(type, (Function<Object, Col<?>>) (Function<?, ?>) column, value));
            return this;
        }

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
