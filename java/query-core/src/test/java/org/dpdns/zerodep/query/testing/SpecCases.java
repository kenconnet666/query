package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.Cte;
import org.dpdns.zerodep.query.Expr;
import org.dpdns.zerodep.query.Query;
import org.dpdns.zerodep.query.Ref;
import org.dpdns.zerodep.query.Window.Bound;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Makt;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.MaraView;
import org.dpdns.zerodep.query.testing.Erp.Vbkd;
import org.dpdns.zerodep.query.testing.RenderSpec.SpecCase;
import org.dpdns.zerodep.query.type.CastType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.dpdns.zerodep.query.Sql.*;

/**
 * The golden-SQL cases every dialect renders, built the Java way. The TypeScript tests build the
 * same cases in test/src/spec-cases.ts; projections name their columns like the TypeScript keys.
 */
public final class SpecCases {
    private SpecCases() { }

    private static final LocalDate DAY = LocalDate.of(2026, 1, 31);

    /** The row of the derived table in the subqueries case. */
    public record OpenLine(String delivery, String material) { }

    public static Map<String, SpecCase> shared() {
        Map<String, SpecCase> cases = new LinkedHashMap<>();
        cases.put("deliveries", SpecCase.plain(() -> Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), null)));
        cases.put("deliveries-filtered", SpecCase.filtered(() -> Scenarios.deliveries(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), "MK1")));
        cases.put("lines-filtered", SpecCase.filtered(Scenarios::lines));
        cases.put("stock", SpecCase.plain(() -> Scenarios.stock("2026")));
        cases.put("latest-deliveries", SpecCase.plain(Scenarios::latestDeliveries));
        cases.put("self-join", SpecCase.plain(() -> from(Mara.class)
            .join(Mara.class, (a, b) -> b.col(Mara::getPartNumber).eq(a, Mara::getPartNumber))
            .where((a, b) -> exists(from(Mara.class).where(c -> c.col(Mara::getMaterial).eq(b, Mara::getMaterial)).selectOne()))
            .selectValue((a, b) -> a.col(Mara::getMaterial))));
        cases.put("paging", SpecCase.plain(() -> from(Mara.class)
            .orderBy(m -> orders(m.col(Mara::getMaterial).asc().nullsLast(), m.col(Mara::getPartNumber).desc().nullsFirst()))
            .selectValue(m -> m.col(Mara::getMaterial))
            .limit(10)
            .offset(20)));
        cases.put("offset-only", SpecCase.plain(() -> from(Mara.class)
            .orderBy(m -> orders(m.col(Mara::getMaterial).asc()))
            .selectValue(m -> m.col(Mara::getMaterial))
            .offset(5)));
        cases.put("conditions", SpecCase.plain(SpecCases::conditions));
        cases.put("case-template", SpecCase.plain(() -> from(Mara.class).selectValue(m -> caseWhen(m.col(Mara::getMaterialType).eq("ZMAT"), "material")
            .when(m.col(Mara::getMaterialType).eq("ZOTH"), "other")
            .otherwise(sql(String.class, "LOWER({0})", m.col(Mara::getMaterialType))))));
        cases.put("literals", SpecCase.plain(() -> from(Mara.class).select((r, m) -> Arrays.asList(
            r.get(lit("O'Brien").as("quote")),
            r.get(lit("Größe").as("unicode")),
            r.get(lit("a\\b").as("backslash")),
            r.get(lit(DAY).as("day")),
            r.get(lit(LocalDateTime.of(2026, 1, 31, 8, 15, 30)).as("moment")),
            r.get(lit(42).as("integer")),
            r.get(lit(new BigDecimal("12.50")).as("decimal")),
            r.get(lit(true).as("flag"))))));
        cases.put("functions", SpecCase.plain(SpecCases::functions));
        cases.put("aggregates", SpecCase.plain(() -> from(Lips.class)
            .groupBy(l -> keys(l.col(Lips::getMaterial)))
            .having(l -> count().gt(1L))
            .select((r, l) -> Arrays.asList(
                r.get(l, Lips::getMaterial),
                r.get(count().as("lines")),
                r.get(countDistinct(l.col(Lips::getSalesOrder)).as("orders")),
                r.get(avg(l.col(Lips::getQuantity)).as("average")),
                r.get(min(l.col(Lips::getQuantity)).as("smallest")),
                r.get(max(l.col(Lips::getQuantity)).as("largest")),
                r.get(sum(l.col(Lips::getQuantity)).as("total"))))));
        cases.put("windows", SpecCase.plain(SpecCases::windows));
        cases.put("values", SpecCase.plain(() -> {
            Cte<Category> categories = cte("category_def", values(Category.class,
                List.of(new Category("1", "自制件", 1), new Category("2", "供应商件", 2))));
            return from(categories)
                .orderBy(c -> orders(c.col(Category::sortNo).asc()))
                .select((r, c) -> Arrays.asList(r.get(c, Category::zfl), r.get(c, Category::name)));
        }));
        cases.put("text-functions", SpecCase.plain(() -> from(Lips.class).select((r, l) -> {
            Expr<BigDecimal> quantity = l.col(Lips::getQuantity);
            return Arrays.asList(
                r.get(left(l.col(Lips::getDelivery), 2).as("prefix")),
                r.get(right(l.col(Lips::getDelivery), 3).as("suffix")),
                r.get(substring(l.col(Lips::getMaterial), 2, 3).as("middle")),
                r.get(substring(l.col(Lips::getMaterial), 2).as("tail")),
                r.get(replace(l.col(Lips::getUnit), "-", "").as("cleaned")),
                r.get(negate(quantity).as("negative")),
                r.get(plus(List.of(quantity, quantity.coalesce(BigDecimal.ZERO))).as("periods")));
        })));
        cases.put("grouped-constants", SpecCase.plain(() -> from(Lips.class)
            .where(l -> l.col(Lips::getUnit).eq("EA"))
            .groupBy(l -> keys(bucket(l), substring(l.col(Lips::getDelivery), 1, 1)))
            .having(l -> count().gt(1L))
            .orderBy(l -> orders(bucket(l).asc()))
            .select((r, l) -> Arrays.asList(
                r.get(bucket(l).as("bucket")),
                r.get(substring(l.col(Lips::getDelivery), 1, 1).as("prefix")),
                r.get(count().as("lines")),
                r.get(sum(caseWhen(l.col(Lips::getUnit).eq("EA"), l.col(Lips::getQuantity)).otherwise(BigDecimal.ZERO)).as("total"))))));
        cases.put("column-transforms", SpecCase.plain(() -> from(MaraView.class)
            .where(m -> all(m.col(MaraView::getMaker).isNotNull(), m.col(MaraView::getStoredMaker).ne("")))
            .groupBy(m -> keys(m.col(MaraView::getPartNumber)))
            .select((r, m) -> Arrays.asList(r.get(m, MaraView::getPartNumber), r.get(countDistinct(m.col(MaraView::getMaker)).as("makers"))))));
        cases.put("rollup", SpecCase.plain(SpecCases::rollup));
        cases.put("subqueries", SpecCase.plain(SpecCases::subqueries));
        return cases;
    }

    /** A code list as constant rows. */
    public record Category(String zfl, String name, Integer sortNo) { }

    private static Expr<String> bucket(Ref<Lips> l) {
        Expr<BigDecimal> quantity = l.col(Lips::getQuantity);
        return caseWhen(quantity.le(new BigDecimal("2")), "small").when(quantity.le(new BigDecimal("5")), "medium").otherwise("large");
    }

    private static Query<String> conditions() {
        return from(Mara.class).where(m -> {
            Expr<String> material = m.col(Mara::getMaterial);
            return all(
                any(material.eq("A"), m.col(Mara::getPartNumber).ne("B").and(m.col(Mara::getManufacturer).isNull())),
                not(m.col(Mara::getMaterialType).in(List.of("ZMAT", "ZOTH"))),
                material.notIn(List.of("X")),
                like(m.col(Mara::getPartNumber), "P%"),
                notLike(m.col(Mara::getManufacturer), "%X"),
                material.between("A", "Z"),
                m.col(Mara::getManufacturer).isNotNull(),
                material.gt("0").and(material.ge("1")).and(material.lt("9")).and(material.le("8")),
                material.eqIfPresent(null),
                likeIfPresent(material, null),
                material.betweenIfPresent("A", null));
        }).selectValue(m -> m.col(Mara::getMaterial));
    }

    private static Query<List<Object>> functions() {
        return from(Lips.class).select((r, l) -> {
            Expr<BigDecimal> quantity = l.col(Lips::getQuantity);
            return Arrays.asList(
                r.get(upper(l.col(Lips::getMaterial)).as("upper")),
                r.get(lower(l.col(Lips::getUnit)).as("lower")),
                r.get(trim(l.col(Lips::getDelivery)).as("trimmed")),
                r.get(concat(l.col(Lips::getMaterial), val("-"), l.col(Lips::getUnit)).as("joined")),
                r.get(l.col(Lips::getUnit).coalesce("EA").as("fallback")),
                r.get(l.col(Lips::getUnit).nullif("").as("emptyUnit")),
                r.get(plus(quantity, quantity).as("total")),
                r.get(minus(quantity, val(1)).as("difference")),
                r.get(times(quantity, val(2)).as("product")),
                r.get(divide(quantity, val(3)).as("ratio")),
                r.get(abs(quantity).as("magnitude")),
                r.get(round(quantity, 1).as("rounded")),
                r.get(cast(quantity, CastType.text(20)).as("asText")),
                r.get(l.col(Lips::getMaterial).cast(CastType.decimal(15, 3)).as("asDecimal")),
                r.get(cast(quantity, CastType.integer()).as("asInteger")),
                r.get(cast(quantity, CastType.doublePrecision()).as("asDouble")),
                r.get(cast(l.col(Lips::getSalesOrder), CastType.date()).as("asDate")),
                r.get(cast(l.col(Lips::getSalesOrder), CastType.timestamp()).as("asTimestamp")));
        });
    }

    private static Query<List<Object>> windows() {
        return from(Lips.class).select((r, l) -> {
            Expr<String> material = l.col(Lips::getMaterial);
            Expr<String> delivery = l.col(Lips::getDelivery);
            Expr<BigDecimal> quantity = l.col(Lips::getQuantity);
            return Arrays.asList(
                r.get(delivery),
                r.get(rowNumber().over(w -> w.partitionBy(material).orderBy(delivery.asc())).as("position")),
                r.get(rank().over(w -> w.orderBy(quantity.desc())).as("ranking")),
                r.get(denseRank().over(w -> w.orderBy(quantity.desc())).as("dense")),
                r.get(lag(delivery, 1).over(w -> w.orderBy(delivery.asc())).as("previous")),
                r.get(lead(delivery, 2).over(w -> w.orderBy(delivery.asc())).as("next")),
                r.get(sum(quantity).over(w -> w.partitionBy(material).orderBy(delivery.asc())
                    .rows(Bound.unboundedPreceding(), Bound.currentRow())).as("running")),
                r.get(avg(quantity).over(w -> w.orderBy(delivery.asc()).rows(Bound.preceding(2), Bound.following(1))).as("moving")));
        });
    }

    /** Totals per material and unit, subtotals per material, and the grand total. */
    public static Query<List<Object>> rollup() {
        return from(Lips.class)
            .groupByRollup(l -> keys(l.col(Lips::getMaterial), l.col(Lips::getUnit)))
            .orderBy(l -> orders(grouping(l.col(Lips::getMaterial)).asc(), l.col(Lips::getMaterial).asc(), grouping(l.col(Lips::getUnit)).asc()))
            .select((r, l) -> Arrays.asList(r.get(l, Lips::getMaterial), r.get(l, Lips::getUnit), r.get(sum(l.col(Lips::getQuantity)).as("total")),
                r.get(grouping(l.col(Lips::getMaterial)).as("level"))));
    }

    private static Query<List<Object>> subqueries() {
        Query<String> parts = from(Mara.class).where(m -> m.col(Mara::getMaterialType).eq("ZMAT")).selectValue(m -> m.col(Mara::getMaterial));
        Query<OpenLine> open = from(Lips.class).selectAs(OpenLine.class, l -> keys(l.col(Lips::getDelivery), l.col(Lips::getMaterial)));
        return from(open.asTable())
            .where(o -> all(o.col(OpenLine::material).in(parts),
                notExists(from(Vbkd.class).where(v -> v.col(Vbkd::getSalesOrder).eq(o, OpenLine::delivery)).selectOne())))
            .select((r, o) -> Arrays.asList(
                r.get(o, OpenLine::delivery),
                r.get(scalar(from(Makt.class).where(mk -> mk.col(Makt::getMaterial).eq(o, OpenLine::material))
                    .selectValue(mk -> max(mk.col(Makt::getText)))).as("text"))));
    }
}
