package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Where;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.expr.typed.ExprDecimal;
import org.dpdns.zerodep.query.expr.typed.ExprInteger;
import org.dpdns.zerodep.query.expr.typed.ExprText;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Event;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Makt;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.MaraView;
import org.dpdns.zerodep.query.testing.Erp.Order;
import org.dpdns.zerodep.query.testing.Erp.Vbkd;
import org.dpdns.zerodep.query.testing.RenderSpec.SpecCase;
import org.dpdns.zerodep.query.type.CastType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;

/**
 * 每个方言都要渲染的金样例，用 Java 的写法构造。TypeScript 在 test/src/spec-cases.ts 构造同样的用例；
 * 投影的列名与 TypeScript 的键一致。
 */
public final class SpecCases {
    private SpecCases() { }

    private static final LocalDate DAY = LocalDate.of(2026, 1, 31);

    /** subqueries 用例里派生表的一行。 */
    public record OpenLine(String delivery, String material) { }

    /** 三个方言共有的用例，按金样例文件名作键。 */
    public static Map<String, SpecCase> shared() {
        Map<String, SpecCase> cases = new LinkedHashMap<>();
        cases.put("deliveries", SpecCase.plain(() -> Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null)));
        cases.put("deliveries-filtered", SpecCase.filtered(() -> Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "MK1")));
        cases.put("lines-filtered", SpecCase.filtered(Scenarios::lines));
        cases.put("stock", SpecCase.plain(() -> Scenarios.stock("2026")));
        cases.put("latest-deliveries", SpecCase.plain(Scenarios::latestDeliveries));
        cases.put("self-join", SpecCase.plain(() -> from(Mara.class)
            .join(Mara.class, (a, b) -> b.partNumber().eq(a.partNumber()))
            .where((a, b) -> exists(from(Mara.class).where(c -> c.material().eq(b.material()))))
            .select((a, b) -> a.material())));
        cases.put("paging", SpecCase.plain(() -> from(Mara.class)
            .orderBy(m -> orders(m.material().asc().nullsLast(), m.partNumber().desc().nullsFirst()))
            .select(m -> m.material())
            .limit(10)
            .offset(20)));
        cases.put("offset-only", SpecCase.plain(() -> from(Mara.class)
            .orderBy(m -> m.material())
            .select(m -> m.material())
            .offset(5)));
        cases.put("conditions", SpecCase.plain(SpecCases::conditions));
        cases.put("case-template", SpecCase.plain(() -> from(Mara.class).select(m -> caseWhen(() -> m.materialType().eq("ZMAT"), "material")
            .when(() -> m.materialType().eq("ZOTH"), "other")
            .otherwise(sql(String.class, "LOWER({0})", m.materialType())))));
        cases.put("literals", SpecCase.plain(() -> from(Mara.class).select(m -> cols(
            lit("O'Brien").as("quote"),
            lit("Größe").as("unicode"),
            lit("a\\b").as("backslash"),
            lit(DAY).as("day"),
            lit(LocalDateTime.of(2026, 1, 31, 8, 15, 30)).as("moment"),
            lit(42).as("integer"),
            lit(new BigDecimal("12.5")).as("decimal"),
            lit(true).as("flag")))));
        cases.put("functions", SpecCase.plain(SpecCases::functions));
        cases.put("aggregates", SpecCase.plain(() -> from(Lips.class)
            .groupBy(l -> l.material())
            .having(l -> count().gt(1L))
            .select(l -> cols(
                l.material(),
                count().as("lines"),
                l.salesOrder().countDistinct().as("orders"),
                l.quantity().avg().as("average"),
                l.quantity().min().as("smallest"),
                l.quantity().max().as("largest"),
                l.quantity().sum().as("total")))));
        cases.put("windows", SpecCase.plain(SpecCases::windows));
        cases.put("values", SpecCase.plain(() -> {
            Cte<Category> categories = cte("category_def", values(Category.class,
                List.of(new Category("1", "自制件", 1), new Category("2", "供应商件", 2))));
            return from(categories)
                .orderBy(c -> c.col(Category::sortNo))
                .select(c -> cols(c.col(Category::zfl), c.col(Category::name)));
        }));
        cases.put("text-functions", SpecCase.plain(() -> from(Lips.class).select(l -> {
            ExprDecimal<BigDecimal> quantity = l.quantity();
            return cols(
                l.delivery().left(2).as("prefix"),
                l.delivery().right(3).as("suffix"),
                l.material().substring(2, 3).as("middle"),
                l.material().substring(2).as("tail"),
                l.unit().replace("-", "").as("cleaned"),
                quantity.negate().as("negative"),
                quantity.plus(quantity.coalesce(BigDecimal.ZERO)).as("periods"));
        })));
        cases.put("grouped-constants", SpecCase.plain(() -> from(Lips.class)
            .where(l -> l.unit().eq("EA"))
            .groupBy(l -> keys(bucket(l), l.delivery().substring(1, 1)))
            .having(l -> count().gt(1L))
            .orderBy(l -> bucket(l))
            .select(l -> cols(
                bucket(l).as("bucket"),
                l.delivery().substring(1, 1).as("prefix"),
                count().as("lines"),
                caseWhen(() -> l.unit().eq("EA"), l.quantity()).otherwise(BigDecimal.ZERO).sum().as("total")))));
        cases.put("column-transforms", SpecCase.plain(() -> from(MaraView.class)
            .where(m -> { m.maker().isNotNull(); m.storedMaker().ne(""); })
            .groupBy(m -> m.partNumber())
            .select(m -> cols(m.partNumber(), m.maker().countDistinct().as("makers")))));
        cases.put("rollup", SpecCase.plain(SpecCases::rollup));
        cases.put("union-grouping", SpecCase.plain(SpecCases::unionGrouping));
        cases.put("text-positions", SpecCase.plain(SpecCases::textPositions));
        cases.put("simple-case", SpecCase.plain(SpecCases::simpleCase));
        cases.put("floor-ceil", SpecCase.plain(SpecCases::floorCeil));
        cases.put("reserved-alias", SpecCase.plain(SpecCases::reservedAlias));
        cases.put("subqueries", SpecCase.plain(SpecCases::subqueries));
        cases.put("dynamic-columns", SpecCase.plain(SpecCases::dynamicColumns));
        cases.put("filter-object", SpecCase.plain(() -> filterObject(new DeliverySearch(List.of("M1", "M2"), LocalDate.of(2026, 1, 1), null, "50%", "KG"))));
        cases.put("native-sql", SpecCase.plain(SpecCases::nativeSql));
        cases.put("filter-many", SpecCase.filtered(() -> filterMany(new MaterialSearch("EA", new BigDecimal("5"), "ZMAT"))));
        cases.put("navigation", SpecCase.filtered(SpecCases::navigation));
        cases.put("one-to-many", SpecCase.filtered(SpecCases::oneToMany));
        cases.put("group-join", SpecCase.filtered(SpecCases::groupJoin));
        cases.put("workflow-todo", SpecCase.plain(() -> Workflow.todo("alice", List.of("managers", "leads"), new Workflow.TodoSearch("leave", "app", null))));
        cases.put("workflow-variables", SpecCase.plain(Workflow::instancesByVariables));
        cases.put("workflow-variables-grouped", SpecCase.plain(Workflow::instancesByVariablesGrouped));
        cases.put("workflow-variables-joined", SpecCase.plain(Workflow::instancesByVariablesJoined));
        cases.put("workflow-latest", SpecCase.plain(Workflow::latestDefinitions));
        cases.put("workflow-latest-ranked", SpecCase.plain(Workflow::latestDefinitionsRanked));
        cases.put("workflow-statistics", SpecCase.plain(Workflow::definitionStatistics));
        cases.put("workflow-workload", SpecCase.plain(Workflow::workload));
        cases.put("workflow-elapsed", SpecCase.plain(Workflow::elapsed));
        cases.put("write-insert", SpecCase.plain(() -> insertInto(Writes.Note.class).values(new Writes.NoteRow("N1", "T1", "first", 1, "open"))));
        cases.put("write-insert-select", SpecCase.plain(Writes::copyNote));
        cases.put("write-update", SpecCase.plain(() -> Writes.closeOpenNote("N1")));
        cases.put("write-join-update", SpecCase.plain(Writes::syncNoteTitles));
        cases.put("write-join-delete", SpecCase.plain(() -> Writes.deleteNotesOfDocs("gone")));
        cases.put("write-join-logic-delete", SpecCase.plain(() -> Writes.deleteTicketsOfDocs("gone")));
        cases.put("write-navigation", SpecCase.filtered(() -> update(Lips.class).set(l -> l.unit().to("EA")).where(l -> l.text().text().isNull())));
        cases.put("write-delete", SpecCase.plain(Writes::deleteClosed));
        cases.put("write-update-by-id", SpecCase.plain(() -> Writes.renameDoc(new Writes.DocRow("D1", "new", 3))));
        cases.put("write-delete-by-id", SpecCase.plain(() -> Writes.deleteDoc(new Writes.DocRow("D1", null, 3))));
        cases.put("write-upsert", SpecCase.plain(() -> Writes.upsertDoc(new Writes.DocRow("D1", "new", 1))));
        cases.put("write-upsert-nothing", SpecCase.plain(() -> Writes.insertDocIfAbsent(new Writes.DocRow("D1", "new", 1))));
        cases.put("workflow-claim", SpecCase.plain(Workflow::claimable));
        cases.put("write-logic-delete", SpecCase.plain(() -> Writes.deleteTicket("T1")));
        cases.put("logic-delete-query", SpecCase.plain(() -> from(Writes.Ticket.class).orderBy(t -> t.id()).select(t -> t.id())));
        cases.put("write-insert-generated", SpecCase.plain(() -> insertInto(Writes.Seq.class).values(new Writes.SeqRow("a"))));
        cases.put("date-functions", SpecCase.plain(SpecCases::dateFunctions));
        cases.put("time-functions", SpecCase.plain(SpecCases::timeFunctions));
        cases.put("number-text-functions", SpecCase.plain(SpecCases::numberTextFunctions));
        cases.put("conditional-aggregates", SpecCase.plain(SpecCases::conditionalAggregates));
        cases.put("qualify", SpecCase.plain(SpecCases::qualify));
        cases.put("window-values", SpecCase.plain(SpecCases::windowValues));
        cases.put("many-text", SpecCase.filtered(SpecCases::manyText));
        cases.put("set-operations", SpecCase.plain(SpecCases::setOperations));
        cases.put("subquery-comparisons", SpecCase.filtered(SpecCases::subqueryComparisons));
        cases.put("statistics", SpecCase.plain(SpecCases::statistics));
        cases.put("distribution-windows", SpecCase.plain(SpecCases::distributionWindows));
        cases.put("shard-month-range", SpecCase.plain(() -> from(Shards.ShEvent.class)
            .where(e -> e.day().range(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 1)))
            .groupBy(e -> e.note())
            .select(e -> cols(e.note(), e.amount().sum().as("total")))));
        cases.put("shard-single", SpecCase.plain(() -> from(Shards.ShEvent.class)
            .where(e -> { e.day().eq(LocalDate.of(2026, 2, 10)); e.note().like("a%"); })
            .select(e -> e.id())));
        cases.put("shard-modulo-in", SpecCase.plain(() -> from(Shards.ShOrder.class)
            .where(o -> any(() -> { o.customer().in(List.of(1, 3)); o.customer().eq(5); }))
            .select(o -> cols(o.customer(), o.total()))));
        cases.put("shard-all", SpecCase.plain(() -> from(Shards.ShOrder.class).allShards().select(o -> count())));
        cases.put("shard-update", SpecCase.plain(() -> update(Shards.ShEvent.class)
            .set(e -> e.note().to("x"))
            .where(e -> e.day().between(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)))));
        return cases;
    }

    /** 码表作为常量行。 */
    public record Category(String zfl, String name, Integer sortNo) { }

    private static ExprText<String> bucket(Lips l) {
        ExprDecimal<BigDecimal> quantity = l.quantity();
        return caseWhen(() -> quantity.le(new BigDecimal("2")), "small").when(() -> quantity.le(new BigDecimal("5")), "medium").otherwise("large");
    }

    /** 各种条件；skipEmpty 下与空值比较的条件被省略，between 只留下有值的一边。 */
    private static Query<String> conditions() {
        String missing = null;
        return from(Mara.class).skipEmpty().where(m -> {
            ExprText<String> material = m.material();
            any(() -> {
                material.eq("A");
                all(() -> {
                    m.partNumber().ne("B");
                    m.manufacturer().isNull();
                });
            });
            not(() -> m.materialType().in(List.of("ZMAT", "ZOTH")));
            material.notIn(List.of("X"));
            m.partNumber().like("P%");
            m.manufacturer().notLike("%X");
            material.between("A", "Z");
            m.manufacturer().isNotNull();
            material.gt("0");
            material.ge("1");
            material.lt("9");
            material.le("8");
            material.eq(missing);
            material.like(missing);
            material.between("A", missing);
        }).select(m -> m.material());
    }

    private static Query<Map<String, Object>> functions() {
        return from(Lips.class).select(l -> {
            ExprDecimal<BigDecimal> quantity = l.quantity();
            return cols(
                l.material().upper().as("upper"),
                l.unit().lower().as("lower"),
                l.delivery().trim().as("trimmed"),
                l.material().concat(val("-"), l.unit()).as("joined"),
                l.unit().coalesce("EA").as("fallback"),
                l.unit().nullif("").as("emptyUnit"),
                quantity.plus(quantity).as("total"),
                quantity.minus(val(1)).as("difference"),
                quantity.times(val(2)).as("product"),
                quantity.divide(val(3)).as("ratio"),
                quantity.abs().as("magnitude"),
                quantity.round(1).as("rounded"),
                quantity.cast(CastType.text(20)).as("asText"),
                l.material().cast(CastType.decimal(15, 3)).as("asDecimal"),
                quantity.cast(CastType.integer()).as("asInteger"),
                quantity.cast(CastType.doublePrecision()).as("asDouble"),
                l.salesOrder().cast(CastType.date()).as("asDate"),
                l.salesOrder().cast(CastType.timestamp()).as("asTimestamp"));
        });
    }

    private static Query<Map<String, Object>> windows() {
        return from(Lips.class).select(l -> {
            ExprText<String> material = l.material();
            ExprText<String> delivery = l.delivery();
            ExprDecimal<BigDecimal> quantity = l.quantity();
            return cols(
                delivery,
                rowNumber().over(w -> w.partitionBy(material).orderBy(delivery)).as("position"),
                rank().over(w -> w.orderBy(quantity.desc())).as("ranking"),
                denseRank().over(w -> w.orderBy(quantity.desc())).as("dense"),
                lag(delivery, 1).over(w -> w.orderBy(delivery)).as("previous"),
                lead(delivery, 2).over(w -> w.orderBy(delivery)).as("next"),
                quantity.sum().over(w -> w.partitionBy(material).orderBy(delivery)
                    .rows(Bound.unboundedPreceding(), Bound.currentRow())).as("running"),
                quantity.avg().over(w -> w.orderBy(delivery).rows(Bound.preceding(2), Bound.following(1))).as("moving"));
        });
    }

    /** 按物料和单位的合计、每个物料的小计，以及总计。 */
    public static Query<Map<String, Object>> rollup() {
        return from(Lips.class)
            .groupByRollup(l -> keys(l.material(), l.unit()))
            .orderBy(l -> orders(grouping(l.material()), l.material(), grouping(l.unit())))
            .select(l -> cols(l.material(), l.unit(), l.quantity().sum().as("total"), grouping(l.material()).as("level")));
    }

    /** 逗号分隔的代码，SAP 在一个字段里存多个 ATA 章节。 */
    public record Code(String text) { }

    /** 在 LOCATE 找到的逗号处切开每个代码：起止位置是表达式的 SUBSTRING。 */
    public static Query<Map<String, Object>> textPositions() {
        Cte<Code> codes = cte("codes", values(Code.class, List.of(new Code("21,32"), new Code("05"))));
        return from(codes)
            .orderBy(c -> c.col(Code::text))
            .select(c -> {
                ExprText<String> text = c.col(Code::text);
                ExprInteger<Integer> comma = text.locate(",");
                return cols(text.as("text"), comma.as("comma"),
                    caseWhen(() -> comma.gt(0), text.substring(lit(1), comma.minus(lit(1)))).otherwise(text).as("head"),
                    caseWhen(() -> comma.gt(0), text.substring(comma.plus(lit(1)))).end().as("tail"));
            });
    }

    /** 内联的码表，以及多个值里取第一个有值的。 */
    public static Query<Map<String, Object>> simpleCase() {
        return from(Mara.class)
            .orderBy(m -> m.material())
            .select(m -> cols(m.material(),
                caseOf(m.materialType()).when("ZMAT", "material").otherwise("other").as("kind"),
                m.manufacturer().nullif("").coalesce(m.partNumber().nullif(""), val("none")).as("maker")));
    }

    /** 向下取整与向上取整。 */
    public static Query<Map<String, Object>> floorCeil() {
        return from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> {
                ExprDecimal<BigDecimal> half = l.quantity().divide(lit(new BigDecimal("2")));
                return cols(l.delivery(), half.floor().as("down"), half.ceil().as("up"));
            });
    }

    /** 排除分组维度和导航列后按用户的排序串排序：分组键、排序和连接都随之去掉。 */
    public static Query<Map<String, Object>> dynamicColumns() {
        return from(Lips.class)
            .groupBy(l -> keys(l.material(), l.unit(), l.mara().partNumber()))
            .orderBy(l -> orders(l.material().asc(), l.unit().asc()))
            .select(l -> cols(l.material(), l.unit(), l.mara().partNumber(), l.quantity().sum().as("total")))
            .exclude("unit", "partNumber")
            .sortBy("total desc, material");
    }

    /** 交货查询的请求参数：每个非空字段是一个条件。 */
    public record DeliverySearch(
        List<String> material,
        @Where(value = "createdOn", op = Where.Op.GE) LocalDate from,
        @Where(value = "createdOn", op = Where.Op.LT) LocalDate to,
        @Where(value = {"mara.partNumber", "text.text"}, op = Where.Op.CONTAINS) String keyword,
        @Where(op = Where.Op.NE) String unit) { }

    /** 按条件对象筛选交货单。 */
    public static Query<String> filterObject(DeliverySearch search) {
        return from(Lips.class).where(search).orderBy(l -> l.delivery()).select(l -> l.delivery());
    }

    /** 物料查询的请求参数：两个字段走同一个一对多关系，合并进同一个 EXISTS。 */
    public record MaterialSearch(
        @Where("deliveries.unit") String unit,
        @Where(value = "deliveries.quantity", op = Where.Op.GE) BigDecimal minQuantity,
        String materialType) { }

    /** 按交货行的条件筛选物料。 */
    public static Query<String> filterMany(MaterialSearch search) {
        return from(Mara.class).where(search).orderBy(m -> m.material()).select(m -> m.material());
    }

    /** 原生 SQL 数据源的一行：物料和数量合计。 */
    public record MaterialTotal(String material, BigDecimal total) { }

    /** 原生 SQL：标识符按方言加引号的 sqlTable、集合参数展开、混用表达式和值的 sql(...) 与 sqlCond(...)。 */
    public static Query<Map<String, Object>> nativeSql() {
        var totals = sqlTable(MaterialTotal.class, "SELECT {0} AS {1}, SUM({2}) AS {3} FROM {4}.{5} WHERE {6} IN ({7}) GROUP BY {0}",
            identifier("MATNR"), identifier("material"), identifier("LFIMG"), identifier("total"), identifier("QUERY_IT"), identifier("LIPS"),
            identifier("VRKME"), List.of("EA", "KG"));
        return from(totals)
            .join(Mara.class, (t, m) -> m.material().eq(t.col(MaterialTotal::material)))
            .where((t, m) -> sqlCond("{0} <> {1}", m.materialType(), "ZOTH"))
            .orderBy((t, m) -> t.col(MaterialTotal::material))
            .select((t, m) -> cols(t.col(MaterialTotal::material), sql(String.class, "LPAD({0}, {1}, '0')", m.material(), 10).as("padded"),
                t.col(MaterialTotal::total)));
    }

    /** 多对一导航：按需 LEFT JOIN，可以继续导航，全局过滤器进入 ON。 */
    public static Query<Map<String, Object>> navigation() {
        return from(Lips.class)
            .where(l -> l.mara().manufacturer().eq("MK1"))
            .orderBy(l -> l.delivery())
            .select(l -> cols(l.delivery(), l.mara().partNumber(), l.mara().text().text()));
    }

    /** 一对多：EXISTS、相关子查询（行数、合计）和每组第一行。 */
    public static Query<Map<String, Object>> oneToMany() {
        return from(Mara.class)
            .where(m -> { m.materialType().eq("ZMAT"); m.deliveries().any(l -> l.unit().eq("EA")); })
            .orderBy(m -> m.material())
            .select(m -> cols(m.material(), m.deliveries().count().as("lines"), m.deliveries().sum(l -> l.quantity()).as("total"),
                m.deliveries().first(l -> l.createdOn().desc()).delivery().as("latest")));
    }

    /** 日期部分、日期加减和相隔天数，按半开区间筛选。 */
    public static Query<Map<String, Object>> dateFunctions() {
        return from(Event.class)
            .where(e -> e.onDay().range(LocalDate.of(2011, 1, 1), LocalDate.of(2025, 1, 1)))
            .orderBy(e -> e.id())
            .select(e -> cols(e.id(), e.onDay().year().as("y"), e.atTime().month().as("m"), e.onDay().day().as("d"),
                e.onDay().addDays(3).as("plusDays"), e.onDay().addMonths(-1).as("minusMonth"), e.onDay().addYears(1).as("plusYear"),
                e.onDay().daysBetween(lit(LocalDate.of(2024, 4, 30))).as("days")));
    }

    /** 时间部分、加减小时分钟秒与月初。 */
    public static Query<Map<String, Object>> timeFunctions() {
        return from(Event.class)
            .orderBy(e -> e.id())
            .select(e -> cols(e.id(), e.atTime().hour().as("h"), e.atTime().minute().as("mi"), e.atTime().second().as("s"),
                e.atTime().addHours(25).as("plusHours"), e.atTime().addMinutes(-31).as("minusMinutes"), e.atTime().addSeconds(59).as("plusSeconds"),
                e.onDay().monthStart().as("monthStart")));
    }

    /** 补齐、余数、乘方、开方与符号。 */
    public static Query<Map<String, Object>> numberTextFunctions() {
        return from(Lips.class)
            .where(l -> l.delivery().eq("D1"))
            .select(l -> cols(l.material().lpad(5, "0").as("padded"), l.unit().rpad(4, "*").as("unit"), l.quantity().mod(2).as("remainder"),
                l.quantity().power(2).as("squared"), l.quantity().sqrt().as("root"), l.quantity().minus(lit(new BigDecimal("4"))).sign().as("sign")));
    }

    /** 每个物料最新的两张交货单：窗口条件包成派生表，不在结果里的排序列作为额外的列带出来。 */
    public static Query<Map<String, Object>> qualify() {
        return from(Lips.class)
            .qualify(l -> rowNumber().over(w -> w.partitionBy(l.material()).orderBy(l.createdOn().desc())).le(2L))
            .orderBy(l -> orders(l.material(), l.createdOn().desc()))
            .select(l -> cols(l.material(), l.delivery()));
    }

    /** 窗口里的首行、末行（整个分区）、带默认值的上一行和分组号。 */
    public static Query<Map<String, Object>> windowValues() {
        return from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> cols(l.delivery(),
                firstValue(l.quantity()).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())).as("first"),
                lastValue(l.quantity()).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())
                    .rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())).as("last"),
                lag(l.quantity(), 1, BigDecimal.ZERO).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())).as("previous"),
                ntile(2).over(w -> w.orderBy(l.delivery())).as("half")));
    }

    /**
     * INTERSECT、EXCEPT 与 UNION 混用：{@code (交货的物料 ∪ 主数据的物料) ∩ ZMAT 物料 − (X − Y)}，
     * 先算的部分都写出括号，三库读法一致。
     */
    public static Query<String> setOperations() {
        Query<String> delivered = from(Lips.class).select(l -> l.material());
        Query<String> master = from(Mara.class).select(m -> m.material());
        Query<String> zmat = from(Mara.class).where(m -> m.materialType().eq("ZMAT")).select(m -> m.material());
        Query<String> x = from(Mara.class).where(m -> m.manufacturer().eq("X")).select(m -> m.material());
        Query<String> y = from(Mara.class).where(m -> m.manufacturer().eq("Y")).select(m -> m.material());
        return delivered.union(master).intersect(zmat).except(x.except(y));
    }

    /**
     * RIGHT JOIN 与子查询比较：主数据的过滤条件挪进 RIGHT JOIN 的 ON；多列 IN、ANY、ALL、
     * NULL 也相等的比较，以及条件作为布尔列。
     */
    public static Query<Map<String, Object>> subqueryComparisons() {
        return from(Mara.class)
            .rightJoin(Lips.class, (m, l) -> m.material().eq(l.material()))
            .where((m, l) -> {
                keys(l.delivery(), l.material()).in(from(Lips.class).where(x -> x.unit().eq("EA")).select(x -> cols(x.delivery(), x.material())));
                l.quantity().ge(anyOf(from(Lips.class).where(x -> x.unit().eq("KG")).select(x -> x.quantity())));
                l.quantity().lt(allOf(from(Lips.class).where(x -> x.unit().eq("PC")).select(x -> x.quantity())));
                not(() -> m.manufacturer().isNotDistinctFrom(m.partNumber()));
                l.unit().isDistinctFrom(m.materialType());
            })
            .orderBy((m, l) -> l.delivery())
            .select((m, l) -> cols(l.delivery(), m.partNumber(), bool(() -> l.quantity().gt(BigDecimal.ONE)).as("big")));
    }

    /** 去重的求和与平均、样本与总体的标准差和方差。 */
    public static Query<Map<String, Object>> statistics() {
        return from(Lips.class)
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> cols(l.material(),
                l.quantity().sumDistinct().as("distinctTotal"),
                l.quantity().avgDistinct().as("distinctAverage"),
                l.quantity().stddevSamp().as("sampleDeviation"),
                l.quantity().stddevPop().as("populationDeviation"),
                l.quantity().varSamp().as("sampleVariance"),
                l.quantity().varPop().as("populationVariance")));
    }

    /** 相对排名、累计分布和窗口里的第 n 行。 */
    public static Query<Map<String, Object>> distributionWindows() {
        return from(Lips.class)
            .orderBy(l -> l.delivery())
            .select(l -> cols(l.delivery(),
                percentRank().over(w -> w.orderBy(l.quantity())).as("percentRank"),
                cumeDist().over(w -> w.partitionBy(l.material()).orderBy(l.quantity())).as("cumeDist"),
                nthValue(l.delivery(), 2).over(w -> w.partitionBy(l.material()).orderBy(l.delivery())
                    .rows(Bound.unboundedPreceding(), Bound.unboundedFollowing())).as("second")));
    }

    /** 一对多上的去重计数和按顺序拼接的文本。 */
    public static Query<Map<String, Object>> manyText() {
        return from(Mara.class)
            .orderBy(m -> m.material())
            .select(m -> cols(m.material(),
                m.deliveries().countDistinct(l -> l.salesOrder()).as("orders"),
                m.deliveries().orderBy(l -> l.delivery()).stringAgg(l -> l.delivery(), ",").as("deliveries")));
    }

    /** 条件聚合与文本拼接：每个物料按单位分开合计。 */
    public static Query<Map<String, Object>> conditionalAggregates() {
        return from(Lips.class)
            .groupBy(l -> l.material())
            .orderBy(l -> l.material())
            .select(l -> cols(l.material(),
                l.quantity().sum().filter(() -> l.unit().eq("KG")).as("kg"),
                count().filter(() -> l.unit().eq("EA")).as("eachLines"),
                l.unit().countDistinct().filter(() -> l.quantity().gt(BigDecimal.ZERO)).as("units"),
                l.delivery().stringAgg(",", l.delivery().asc()).as("deliveries")));
    }

    /** 同一个一对多关系上的存在判断、行数、合计和带筛选的行数，合成一个按连接键分组的派生表。 */
    public static Query<Map<String, Object>> groupJoin() {
        return from(Mara.class)
            .groupJoin(m -> m.deliveries())
            .where(m -> m.deliveries().any(l -> l.unit().eq("EA")))
            .orderBy(m -> m.material())
            .select(m -> cols(m.material(), m.deliveries().count().as("lines"), m.deliveries().sum(l -> l.quantity()).as("total"),
                m.deliveries().where(l -> l.createdOn().ge(LocalDate.of(2026, 1, 1))).count().as("recent")));
    }

    /** 表名是 Order：别名是保留字，必须加引号。 */
    public static Query<String> reservedAlias() {
        return from(Order.class)
            .where(o -> o.material().eq("M1"))
            .orderBy(o -> o.delivery())
            .select(o -> o.delivery());
    }

    /** a UNION ALL (b UNION c)：右侧的 UNION 保留括号，所以 a 与 c 共有的行出现两次。 */
    public static Query<String> unionGrouping() {
        return materialOf("D1").unionAll(materialOf("D3").union(materialOf("D2")));
    }

    private static Query<String> materialOf(String delivery) {
        return from(Lips.class).where(l -> l.delivery().eq(delivery)).select(l -> l.material());
    }

    private static Query<Map<String, Object>> subqueries() {
        Query<String> parts = from(Mara.class).where(m -> m.materialType().eq("ZMAT")).select(m -> m.material());
        Query<OpenLine> open = from(Lips.class).select(l -> cols(l.delivery(), l.material())).into(OpenLine.class);
        return from(open.asTable())
            .where(o -> {
                o.col(OpenLine::material).in(parts);
                notExists(from(Vbkd.class).where(v -> v.salesOrder().eq(o.col(OpenLine::delivery))));
            })
            .select(o -> cols(
                o.col(OpenLine::delivery),
                scalar(from(Makt.class).where(mk -> mk.material().eq(o.col(OpenLine::material)))
                    .select(mk -> mk.text().max())).as("text")));
    }
}
