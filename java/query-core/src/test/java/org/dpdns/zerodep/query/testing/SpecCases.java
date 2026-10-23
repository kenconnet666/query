package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.expr.Window.Bound;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
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
        cases.put("case-template", SpecCase.plain(() -> from(Mara.class).select(m -> caseWhen(m.materialType().eq("ZMAT"), "material")
            .when(m.materialType().eq("ZOTH"), "other")
            .otherwise(sql(String.class, "LOWER({0})", m.materialType())))));
        cases.put("literals", SpecCase.plain(() -> from(Mara.class).select(m -> cols(
            lit("O'Brien").as("quote"),
            lit("Größe").as("unicode"),
            lit("a\\b").as("backslash"),
            lit(DAY).as("day"),
            lit(LocalDateTime.of(2026, 1, 31, 8, 15, 30)).as("moment"),
            lit(42).as("integer"),
            lit(new BigDecimal("12.50")).as("decimal"),
            lit(true).as("flag")))));
        cases.put("functions", SpecCase.plain(SpecCases::functions));
        cases.put("aggregates", SpecCase.plain(() -> from(Lips.class)
            .groupBy(l -> l.material())
            .having(l -> count().gt(1L))
            .select(l -> cols(
                l.material(),
                count().as("lines"),
                countDistinct(l.salesOrder()).as("orders"),
                avg(l.quantity()).as("average"),
                min(l.quantity()).as("smallest"),
                max(l.quantity()).as("largest"),
                sum(l.quantity()).as("total")))));
        cases.put("windows", SpecCase.plain(SpecCases::windows));
        cases.put("values", SpecCase.plain(() -> {
            Cte<Category> categories = cte("category_def", values(Category.class,
                List.of(new Category("1", "自制件", 1), new Category("2", "供应商件", 2))));
            return from(categories)
                .orderBy(c -> c.col(Category::sortNo))
                .select(c -> cols(c.col(Category::zfl), c.col(Category::name)));
        }));
        cases.put("text-functions", SpecCase.plain(() -> from(Lips.class).select(l -> {
            Expr<BigDecimal> quantity = l.quantity();
            return cols(
                left(l.delivery(), 2).as("prefix"),
                right(l.delivery(), 3).as("suffix"),
                substring(l.material(), 2, 3).as("middle"),
                substring(l.material(), 2).as("tail"),
                replace(l.unit(), "-", "").as("cleaned"),
                negate(quantity).as("negative"),
                plus(List.of(quantity, quantity.coalesce(BigDecimal.ZERO))).as("periods"));
        })));
        cases.put("grouped-constants", SpecCase.plain(() -> from(Lips.class)
            .where(l -> l.unit().eq("EA"))
            .groupBy(l -> keys(bucket(l), substring(l.delivery(), 1, 1)))
            .having(l -> count().gt(1L))
            .orderBy(l -> bucket(l))
            .select(l -> cols(
                bucket(l).as("bucket"),
                substring(l.delivery(), 1, 1).as("prefix"),
                count().as("lines"),
                sum(caseWhen(l.unit().eq("EA"), l.quantity()).otherwise(BigDecimal.ZERO)).as("total")))));
        cases.put("column-transforms", SpecCase.plain(() -> from(MaraView.class)
            .where(m -> all(m.maker().isNotNull(), m.storedMaker().ne("")))
            .groupBy(m -> m.partNumber())
            .select(m -> cols(m.partNumber(), countDistinct(m.maker()).as("makers")))));
        cases.put("rollup", SpecCase.plain(SpecCases::rollup));
        cases.put("union-grouping", SpecCase.plain(SpecCases::unionGrouping));
        cases.put("text-positions", SpecCase.plain(SpecCases::textPositions));
        cases.put("simple-case", SpecCase.plain(SpecCases::simpleCase));
        cases.put("floor-ceil", SpecCase.plain(SpecCases::floorCeil));
        cases.put("reserved-alias", SpecCase.plain(SpecCases::reservedAlias));
        cases.put("subqueries", SpecCase.plain(SpecCases::subqueries));
        return cases;
    }

    /** 码表作为常量行。 */
    public record Category(String zfl, String name, Integer sortNo) { }

    private static Expr<String> bucket(Lips l) {
        Expr<BigDecimal> quantity = l.quantity();
        return caseWhen(quantity.le(new BigDecimal("2")), "small").when(quantity.le(new BigDecimal("5")), "medium").otherwise("large");
    }

    /** 各种条件；skipEmpty 下与空值比较的条件被省略，between 只留下有值的一边。 */
    private static Query<String> conditions() {
        String missing = null;
        return from(Mara.class).skipEmpty().where(m -> {
            Expr<String> material = m.material();
            return all(
                any(material.eq("A"), m.partNumber().ne("B").and(m.manufacturer().isNull())),
                not(m.materialType().in(List.of("ZMAT", "ZOTH"))),
                material.notIn(List.of("X")),
                like(m.partNumber(), "P%"),
                notLike(m.manufacturer(), "%X"),
                material.between("A", "Z"),
                m.manufacturer().isNotNull(),
                material.gt("0").and(material.ge("1")).and(material.lt("9")).and(material.le("8")),
                material.eq(missing),
                like(material, missing),
                material.between("A", missing));
        }).select(m -> m.material());
    }

    private static Query<Map<String, Object>> functions() {
        return from(Lips.class).select(l -> {
            Expr<BigDecimal> quantity = l.quantity();
            return cols(
                upper(l.material()).as("upper"),
                lower(l.unit()).as("lower"),
                trim(l.delivery()).as("trimmed"),
                concat(l.material(), val("-"), l.unit()).as("joined"),
                l.unit().coalesce("EA").as("fallback"),
                l.unit().nullif("").as("emptyUnit"),
                plus(quantity, quantity).as("total"),
                minus(quantity, val(1)).as("difference"),
                times(quantity, val(2)).as("product"),
                divide(quantity, val(3)).as("ratio"),
                abs(quantity).as("magnitude"),
                round(quantity, 1).as("rounded"),
                cast(quantity, CastType.text(20)).as("asText"),
                l.material().cast(CastType.decimal(15, 3)).as("asDecimal"),
                cast(quantity, CastType.integer()).as("asInteger"),
                cast(quantity, CastType.doublePrecision()).as("asDouble"),
                cast(l.salesOrder(), CastType.date()).as("asDate"),
                cast(l.salesOrder(), CastType.timestamp()).as("asTimestamp"));
        });
    }

    private static Query<Map<String, Object>> windows() {
        return from(Lips.class).select(l -> {
            Expr<String> material = l.material();
            Expr<String> delivery = l.delivery();
            Expr<BigDecimal> quantity = l.quantity();
            return cols(
                delivery,
                rowNumber().over(w -> w.partitionBy(material).orderBy(delivery)).as("position"),
                rank().over(w -> w.orderBy(quantity.desc())).as("ranking"),
                denseRank().over(w -> w.orderBy(quantity.desc())).as("dense"),
                lag(delivery, 1).over(w -> w.orderBy(delivery)).as("previous"),
                lead(delivery, 2).over(w -> w.orderBy(delivery)).as("next"),
                sum(quantity).over(w -> w.partitionBy(material).orderBy(delivery)
                    .rows(Bound.unboundedPreceding(), Bound.currentRow())).as("running"),
                avg(quantity).over(w -> w.orderBy(delivery).rows(Bound.preceding(2), Bound.following(1))).as("moving"));
        });
    }

    /** 按物料和单位的合计、每个物料的小计，以及总计。 */
    public static Query<Map<String, Object>> rollup() {
        return from(Lips.class)
            .groupByRollup(l -> keys(l.material(), l.unit()))
            .orderBy(l -> orders(grouping(l.material()), l.material(), grouping(l.unit())))
            .select(l -> cols(l.material(), l.unit(), sum(l.quantity()).as("total"), grouping(l.material()).as("level")));
    }

    /** 逗号分隔的代码，SAP 在一个字段里存多个 ATA 章节。 */
    public record Code(String text) { }

    /** 在 LOCATE 找到的逗号处切开每个代码：起止位置是表达式的 SUBSTRING。 */
    public static Query<Map<String, Object>> textPositions() {
        Cte<Code> codes = cte("codes", values(Code.class, List.of(new Code("21,32"), new Code("05"))));
        return from(codes)
            .orderBy(c -> c.col(Code::text))
            .select(c -> {
                Expr<String> text = c.col(Code::text);
                Expr<Integer> comma = locate(text, ",");
                return cols(text.as("text"), comma.as("comma"),
                    caseWhen(comma.gt(0), substring(text, lit(1), minus(comma, lit(1)))).otherwise(text).as("head"),
                    caseWhen(comma.gt(0), substring(text, plus(comma, lit(1)))).end().as("tail"));
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
                Expr<BigDecimal> half = divide(l.quantity(), lit(new BigDecimal("2")));
                return cols(l.delivery(), floor(half).as("down"), ceil(half).as("up"));
            });
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
            .where(o -> all(o.col(OpenLine::material).in(parts),
                notExists(from(Vbkd.class).where(v -> v.salesOrder().eq(o.col(OpenLine::delivery))))))
            .select(o -> cols(
                o.col(OpenLine::delivery),
                scalar(from(Makt.class).where(mk -> mk.material().eq(o.col(OpenLine::material)))
                    .select(mk -> max(mk.text()))).as("text")));
    }
}
