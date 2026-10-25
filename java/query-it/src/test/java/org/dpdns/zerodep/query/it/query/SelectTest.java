package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.exec.TooManyRowsException;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.it.support.SampleData;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.table.Cte;
import org.dpdns.zerodep.query.testing.Erp.Delivery;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Makt;
import org.dpdns.zerodep.query.testing.Erp.Mara;
import org.dpdns.zerodep.query.testing.Erp.MaraView;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.Order;
import org.dpdns.zerodep.query.testing.Erp.PartTotal;
import org.dpdns.zerodep.query.testing.Erp.Vbkd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.dpdns.zerodep.query.Sql.all;
import static org.dpdns.zerodep.query.Sql.caseWhen;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.countDistinct;
import static org.dpdns.zerodep.query.Sql.cte;
import static org.dpdns.zerodep.query.Sql.exists;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.max;
import static org.dpdns.zerodep.query.Sql.row;
import static org.dpdns.zerodep.query.Sql.sum;
import static org.dpdns.zerodep.query.Sql.trim;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 查询与结果映射：CTE 和 EXISTS、全局过滤器、按名映射到结果类、嵌套结果、列转换、取一行的几种写法、按键装回别的查询的结果。
 * 数据见 {@link SampleData}；共享实例 {@code live.db()} 只看客户端 100。
 */
@LiveTest
@DisplayName("查询与结果映射")
class SelectTest {
    @Parameter Live live;

    @Test
    void 带CTE和EXISTS的交货汇总按零件号合计() {
        // 先在 CTE 里挑出有效物料（ZMAT），零件号去掉首尾空格；再连交货行、按日期和"有销售订单数据"筛选、按零件号合计。
        // 制造商条件为 null 时 skipEmpty 让它不产生条件。
        List<Delivery> all = live.db().list(deliveries(null));
        assertEquals(List.of("P1:Pump:5", "P2:P2:5"), all.stream().map(SelectTest::text).toList(),
            "M1 的 D5 引用的 S9 没有销售订单数据，所以只算 D1、D2；M2 没有语言 1 的描述，名称取零件号；D4 在 2025 年");
        assertEquals(List.of("P2:P2:5"), live.db().list(deliveries("MK2")).stream().map(SelectTest::text).toList());
    }

    /** 2026 年上半年的交货汇总，maker 为 null 时不按制造商筛选。 */
    private static Query<Delivery> deliveries(String maker) {
        Cte<MaterialScope> scope = cte("material_scope", from(Mara.class)
            .skipEmpty()
            .where(m -> all(m.materialType().eq("ZMAT"), m.manufacturer().eq(maker)))
            .distinct()
            .select(m -> {
                Expr<String> part = m.partNumber();
                return cols(m.client(), m.material(), caseWhen(part.ne(trim(part)), trim(part)).otherwise(part).as("partNumber"));
            })
            .into(MaterialScope.class));
        return from(scope)
            .join(Lips.class, (s, l) -> l.client().eq(s.col(MaterialScope::client)).and(l.material().eq(s.col(MaterialScope::material))))
            .leftJoin(Makt.class, (s, l, t) -> t.material().eq(s.col(MaterialScope::material)).and(t.language().eq("1")))
            .where((s, l, t) -> all(
                l.createdOn().between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
                exists(from(Vbkd.class).where(v -> v.salesOrder().eq(l.salesOrder())))))
            .groupBy((s, l, t) -> s.col(MaterialScope::partNumber))
            .orderBy((s, l, t) -> s.col(MaterialScope::partNumber))
            .select((s, l, t) -> row(Delivery::new,
                s.col(MaterialScope::partNumber),
                max(t.text().coalesce(s.col(MaterialScope::partNumber))).as("name"),
                sum(l.quantity()).as("quantity")));
    }

    /** 汇总行写成 零件号:名称:数量。 */
    private static String text(Delivery d) { return d.partNumber() + ":" + d.name() + ":" + d.quantity().stripTrailingZeros().toPlainString(); }

    @Test
    void 全局过滤器只读当前客户端且可以关闭() {
        assertEquals(5L, live.db().count(from(Lips.class).select(l -> l.delivery())));
        assertEquals(6L, live.db().count(from(Lips.class).unfiltered().select(l -> l.delivery())), "客户端 200 的 D6 也算上");
    }

    @Test
    void 按名映射到结果类并按SAP日期转换() {
        // 改名的列写 as；LEFT JOIN 的表上也有全局过滤条件，它进 ON 而不是 WHERE，所以没有描述的行照样保留。
        List<LineVO> rows = live.db().list(from(Lips.class)
            .leftJoin(Makt.class, (l, t) -> t.material().eq(l.material()).and(t.language().eq("1")))
            .orderBy((l, t) -> l.delivery())
            .select((l, t) -> cols(l.delivery(), l.material().as("materialNo"), l.createdOn(), t.text()))
            .into(LineVO.class));
        assertEquals(List.of("D1:M1:2026-01-15:Pump", "D2:M1:2026-01-20:Pump", "D3:M2:2026-02-10:null", "D4:M2:2025-12-31:null", "D5:M1:2026-03-01:Pump"),
            rows.stream().map(r -> r.getDelivery() + ":" + r.getMaterialNo() + ":" + r.getCreatedOn() + ":" + r.getText()).toList());
    }

    @Test
    void SAP日期参数按转换器绑定() {
        assertEquals(List.of("D1"), live.db().list(from(Lips.class).where(l -> l.createdOn().eq(LocalDate.of(2026, 1, 15))).select(l -> l.delivery())));
    }

    @Test
    void 嵌套结果和Java端计算() {
        // row 的第一个参数可以是普通 lambda：数量合计折成万单位放进嵌套的 Amount。
        List<PartTotal> rows = live.db().list(from(Lips.class)
            .join(Mara.class, (l, m) -> m.material().eq(l.material()))
            .groupBy((l, m) -> m.partNumber())
            .orderBy((l, m) -> m.partNumber())
            .select((l, m) -> row((quantity, part) -> new PartTotal(part, new PartTotal.Amount(quantity, quantity.doubleValue() / 10_000)),
                sum(l.quantity()), m.partNumber())));
        assertEquals(List.of("P1:6", "P2:12"), rows.stream().map(r -> r.partNumber().strip() + ":" + r.amount().quantity().stripTrailingZeros().toPlainString()).toList(),
            "M4 的零件号也是 P1，但它没有交货，内连接里不出现");
        assertEquals(0.0006, rows.get(0).amount().wan(), 1e-12);
    }

    @Test
    void 类名是保留字的实体也能查询() {
        // Order 映射到 LIPS，生成的别名 order 是保留字，要加引号。
        assertEquals(List.of("D1", "D2", "D5", "D6"), live.db().list(from(Order.class).where(o -> o.material().eq("M1")).orderBy(o -> o.delivery()).select(o -> o.delivery())),
            "Order 不继承 Client，全局过滤器不管它");
    }

    @Test
    void 取第一行可选的一行与唯一的一行() {
        var deliveries = from(Lips.class).orderBy(l -> l.delivery()).select(l -> l.delivery());
        var none = from(Lips.class).where(l -> l.delivery().eq("none")).select(l -> l.delivery());
        var d1 = from(Lips.class).where(l -> l.delivery().eq("D1")).select(l -> l.delivery());
        assertEquals(Optional.of("D1"), live.db().first(deliveries));
        assertEquals(Optional.empty(), live.db().first(none));
        assertEquals(Optional.of("D1"), live.db().optional(d1));
        assertEquals(Optional.empty(), live.db().optional(none));
        assertThrows(TooManyRowsException.class, () -> live.db().optional(deliveries), "optional 最多一行");
        assertThrows(TooManyRowsException.class, () -> live.db().one(deliveries), "one 必须正好一行");
    }

    @Test
    void 列转换按转换后的值读取且可与原值并存() {
        // MaraView 把零件号去空格（@Trimmed）、空制造商读作 NULL（@BlankAsNull），同时保留制造商的原值列。
        List<String> rows = live.db().list(from(MaraView.class)
            .groupBy(m -> m.partNumber())
            .orderBy(m -> m.partNumber())
            .select(m -> row((part, makers, stored) -> part + ":" + makers + ":" + stored,
                m.partNumber(), countDistinct(m.maker()), countDistinct(m.storedMaker()))));
        assertEquals(List.of("P1:1:2", "P2:1:1", "P3:1:1"), rows, "P1 组有 M1（MK1）和 M4（空串）：去掉空串后只有一个制造商");
        assertEquals(List.of("M4"), live.db().list(from(MaraView.class).where(m -> m.maker().isNull()).select(m -> m.material())));
    }

    /** attach 装回数据的行：物料、它的零件号和交货单。 */
    static final class MaterialHolder {
        final String material;
        String partNumber = "unset";
        List<String> deliveries;

        MaterialHolder(String material) { this.material = material; }
    }

    @Test
    void 按键把另一个查询的结果装回行() {
        // attach 一对一、attachMany 一对多；键为 null 的行和找不到的键都装回 null 或空列表。
        List<MaterialHolder> rows = Stream.of("M1", "M2", "M3", "M9", null).map(MaterialHolder::new).toList();
        live.db().attach(rows, h -> h.material,
            keys -> from(Mara.class).where(m -> m.material().in(keys)).select(m -> cols(m.material(), m.partNumber())),
            r -> (String) r.get("material"), (h, r) -> h.partNumber = r == null ? null : ((String) r.get("partNumber")).strip());
        live.db().attachMany(rows, h -> h.material,
            keys -> from(Lips.class).where(l -> l.material().in(keys)).orderBy(l -> l.delivery()).select(l -> cols(l.material(), l.delivery())),
            r -> (String) r.get("material"), (h, list) -> h.deliveries = list.stream().map(r -> (String) r.get("delivery")).toList());
        assertEquals(List.of("M1:P1:[D1, D2, D5]", "M2:P2:[D3, D4]", "M3:P3:[]", "M9:null:[]", "null:null:[]"),
            rows.stream().map(h -> h.material + ":" + h.partNumber + ":" + h.deliveries).toList());
    }
}
