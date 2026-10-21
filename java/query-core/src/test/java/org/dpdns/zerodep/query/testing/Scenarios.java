package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.Cond;
import org.dpdns.zerodep.query.Cte;
import org.dpdns.zerodep.query.Expr;
import org.dpdns.zerodep.query.Fn;
import org.dpdns.zerodep.query.Query;
import org.dpdns.zerodep.query.Ref;
import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Makt;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.Mardh;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.Mchbh;
import org.dpdns.zerodep.query.testing.Erp.PartTotal;
import org.dpdns.zerodep.query.testing.Erp.PeriodStock;
import org.dpdns.zerodep.query.testing.Erp.StockRow;
import org.dpdns.zerodep.query.testing.Erp.Vbkd;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.dpdns.zerodep.query.Sql.*;

/** Queries written the way users write them; rendered by the unit tests and executed by the live tests. */
public final class Scenarios {
    private Scenarios() { }

    // ---------- business definitions are plain methods ----------

    public static Cond activeMaterial(Ref<Mara> ma) { return ma.col(Mara::getMaterialType).eq("ZMAT"); }

    public static Expr<String> normalizedPart(Expr<String> part) {
        return caseWhen(part.ne(trim(part)), trim(part)).otherwise(part);
    }

    /** CTE, EXISTS, LEFT JOIN, optional filter and GROUP BY, read by a functional projection; labels match the TypeScript keys. */
    public static Query<Delivery> deliveries(LocalDate start, LocalDate end, String maker) {
        Cte<MaterialScope> scope = cte("material_scope", from(Mara.class)
            .where(ma -> all(activeMaterial(ma), ma.col(Mara::getManufacturer).eqIfPresent(maker)))
            .distinct()
            .selectAs(MaterialScope.class, ma -> keys(
                ma.col(Mara::getClient),
                ma.col(Mara::getMaterial),
                normalizedPart(ma.col(Mara::getPartNumber)).as(MaterialScope::partNumber))));
        return from(scope)
            .join(Lips.class, (ms, l) -> l.col(Lips::getClient).eq(ms, MaterialScope::client)
                .and(l.col(Lips::getMaterial).eq(ms, MaterialScope::material)))
            .leftJoin(Makt.class, (ms, l, mk) -> mk.col(Makt::getMaterial).eq(ms, MaterialScope::material)
                .and(mk.col(Makt::getLanguage).eq("1")))
            .where((ms, l, mk) -> all(
                l.col(Lips::getCreatedOn).between(start, end),
                exists(from(Vbkd.class).where(v -> v.col(Vbkd::getSalesOrder).eq(l, Lips::getSalesOrder)).selectOne())))
            .groupBy((ms, l, mk) -> keys(ms.col(MaterialScope::partNumber)))
            .orderBy((ms, l, mk) -> orders(ms.col(MaterialScope::partNumber).asc()))
            .select((r, ms, l, mk) -> new Delivery(
                r.get(ms, MaterialScope::partNumber),
                r.get(max(mk.col(Makt::getText).coalesce(ms.col(MaterialScope::partNumber))).as("name")),
                r.get(sum(l.col(Lips::getQuantity)).as("quantity"))));
    }

    /** One template for every table with the PeriodStock shape; the quantity column differs, so it is a lambda. */
    public static <E extends PeriodStock> Query<StockRow> stockArm(Class<E> table, Fn.F1<Ref<E>, Expr<BigDecimal>> quantity, String year) {
        return from(table)
            .where(h -> h.col(PeriodStock::getYear).eq(year))
            .select((r, h) -> new StockRow(
                r.get(h, PeriodStock::getMaterial), r.get(h, PeriodStock::getYear), r.get(h, PeriodStock::getPeriod),
                r.get(quantity.apply(h).as("quantity"))));
    }

    public static Query<StockRow> stock(String year) {
        return stockArm(Mardh.class, h -> h.col(Mardh::getUnrestricted).coalesce(BigDecimal.ZERO), year)
            .unionAll(stockArm(Mchbh.class, h -> h.col(Mchbh::getBatchUnrestricted).coalesce(BigDecimal.ZERO), year));
    }

    public record RankedDelivery(String material, String delivery, Long rank) { }

    /** The latest delivery per material: ROW_NUMBER in a CTE, then rank = 1. */
    public static Query<String> latestDeliveries() {
        Cte<RankedDelivery> ranked = cte("ranked_delivery", from(Lips.class).selectAs(RankedDelivery.class, l -> keys(
            l.col(Lips::getMaterial),
            l.col(Lips::getDelivery),
            rowNumber().over(w -> w.partitionBy(l.col(Lips::getMaterial))
                .orderBy(l.col(Lips::getCreatedOn).desc(), l.col(Lips::getDelivery).desc())).as(RankedDelivery::rank))));
        return from(ranked)
            .where(x -> x.col(RankedDelivery::rank).eq(1L))
            .orderBy(x -> orders(x.col(RankedDelivery::material).asc()))
            .selectValue(x -> x.col(RankedDelivery::delivery));
    }

    /** Same-named properties picked automatically, a renamed one through @MapFrom. */
    public static Query<LineVO> lines() {
        return from(Lips.class)
            .leftJoin(Makt.class, (l, mk) -> mk.col(Makt::getMaterial).eq(l, Lips::getMaterial).and(mk.col(Makt::getLanguage).eq("1")))
            .orderBy((l, mk) -> orders(l.col(Lips::getDelivery).asc()))
            .selectAs(LineVO.class);
    }

    /** A nested result and a Java-side computation in one projection lambda. */
    public static Query<PartTotal> partTotals() {
        return from(Lips.class)
            .join(Mara.class, (l, ma) -> ma.col(Mara::getMaterial).eq(l, Lips::getMaterial))
            .groupBy((l, ma) -> keys(ma.col(Mara::getPartNumber)))
            .orderBy((l, ma) -> orders(ma.col(Mara::getPartNumber).asc()))
            .select((r, l, ma) -> {
                BigDecimal quantity = r.get(sum(l.col(Lips::getQuantity)));
                String part = r.get(ma, Mara::getPartNumber);
                return new PartTotal(part, new PartTotal.Amount(quantity, quantity == null ? 0 : quantity.doubleValue() / 10_000));
            });
    }
}
