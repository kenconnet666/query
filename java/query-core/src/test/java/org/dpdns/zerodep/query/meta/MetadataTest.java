package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.annotation.BlankAsNull;
import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ReadAs;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Trimmed;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.MaraView;
import org.dpdns.zerodep.query.testing.Erp.Mardh;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.Mchbh;
import org.dpdns.zerodep.query.testing.Erp;
import org.dpdns.zerodep.query.testing.TestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.dpdns.zerodep.query.Sql.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("元数据")
class MetadataTest {

    @Test
    void 表接口的列就是返回Col的方法且包括继承来的列() {
        EntityMeta<Lips> lips = Metadata.entity(Lips.class);
        assertEquals(List.of("client", "createdOn", "delivery", "material", "quantity", "salesOrder", "unit"),
            lips.properties().stream().map(PropertyMeta::name).toList(), "columns sorted by name");
        assertEquals("MANDT", lips.property("client").column());
        assertEquals("QUERY_IT", lips.schema());
        assertEquals("LIPS", lips.table());
    }

    @Test
    void 子接口重新声明列方法可以改列名() {
        assertEquals("LABST", Metadata.entity(Mardh.class).property("quantity").column());
        assertEquals("CLABS", Metadata.entity(Mchbh.class).property("quantity").column());
        assertEquals("LFGJA", Metadata.entity(Mchbh.class).property("year").column(), "inherited columns keep their names");
    }

    @Test
    void record访问器就是属性名() {
        assertEquals("partNumber", Metadata.property(MaterialScope.class, MaterialScope::partNumber).name());
    }

    @Test
    void 组合注解挂载转换器() {
        PropertyMeta p = Metadata.entity(Lips.class).property("createdOn");
        assertEquals(LocalDate.class, p.javaType());
        assertEquals(String.class, p.type().storageType());
        @SuppressWarnings("unchecked")
        var type = (org.dpdns.zerodep.query.type.SqlType<LocalDate>) p.type();
        assertEquals("20260131", type.toDatabase(LocalDate.of(2026, 1, 31)));
        assertNull(type.fromDatabase("00000000"));
    }

    @Test
    void 结果类字段上的组合注解也挂载转换器() {
        assertEquals(String.class, Metadata.entity(SapRow.class).property("day").type().storageType());
    }

    @Test
    void 不是方法引用的lambda被拒绝() {
        Getter<MaterialScope, String> lambda = s -> s.material();
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.property(MaterialScope.class, lambda));
        assertTrue(e.getMessage().contains("method reference"), e.getMessage());
    }

    @Test
    void getter返回类型和字段类型不一致时报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.property(Odd.class, Odd::getCode));
        assertTrue(e.getMessage().contains("returns Integer"), e.getMessage());
    }

    @Test
    void 转换器类型和列类型不一致时扫描报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.entity(WrongConverter.class));
        assertTrue(e.getMessage().contains("converts LocalDate, but the field is String"), e.getMessage());
    }

    @Test
    void 没有Table注解的表接口不能查询() {
        var e = assertThrows(IllegalArgumentException.class, () -> from(Erp.Client.class));
        assertTrue(e.getMessage().contains("has no @Table"), e.getMessage());
    }

    @Test
    void 列方法必须声明值类型() {
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.entity(RawColumn.class));
        assertTrue(e.getMessage().contains("must declare its value type"), e.getMessage());
    }

    @Test
    void Ignore字段不是属性() {
        assertFalse(Metadata.entity(LineVO.class).has("computedLater"));
    }

    @Test
    void 命名策略推导列名() {
        assertEquals("PART_NUMBER", NamingStrategy.UPPER_SNAKE.column("partNumber"));
        assertEquals("part_number", NamingStrategy.LOWER_SNAKE.column("partNumber"));
        assertEquals("part_number2_code", NamingStrategy.LOWER_SNAKE.column("partNumber2Code"));
    }

    /** 结果类：字段上的组合注解。 */
    record SapRow(@Erp.SapDate LocalDate day) { }

    static final class Odd {
        private String code;

        public Integer getCode() { return code == null ? null : code.length(); }
    }

    @Table("X")
    interface WrongConverter extends Entity {
        @Column(value = "ERDAT", converter = Erp.SapDateConverter.class) Col<String> createdOn();
    }

    @Table("X")
    @SuppressWarnings("rawtypes")
    interface RawColumn extends Entity {
        Col raw();
    }

    @Table("X")
    interface TwoReads extends Entity {
        @Column("X") @Trimmed @BlankAsNull Col<String> x();
    }

    @Table("X")
    interface NoPlaceholder extends Entity {
        @Column("X") @ReadAs("TRIM(X)") Col<String> x();
    }

    @Table("ORDERS")
    interface Order extends Entity {
        Col<String> orderNumber();

        @Column("ITEM_COUNT") Col<Integer> items();
    }

    /** ORDERS 的一行。 */
    record OrderRow(String orderNumber, Integer items) { }

    @Test
    void 没写列名的列方法按数据库的命名策略推导() {
        TestDatabase snake = new TestDatabase(c -> c.naming(NamingStrategy.UPPER_SNAKE));
        assertEquals("SELECT \"ORDER\".ORDER_NUMBER AS \"orderNumber\", \"ORDER\".ITEM_COUNT AS \"items\"\nFROM ORDERS \"ORDER\"",
            snake.render(from(Order.class).selectAs(OrderRow.class)).sql());
        assertTrue(new TestDatabase(null).render(from(Order.class).selectAs(OrderRow.class)).sql().startsWith("SELECT \"ORDER\".\"orderNumber\""));
    }

    @Test
    void 读取模板来自列方法或组合注解且必须唯一并包含列占位符() {
        assertEquals("TRIM({0})", Metadata.entity(MaraView.class).property("partNumber").read());
        assertEquals("NULLIF({0}, '')", Metadata.entity(MaraView.class).property("maker").read());
        assertNull(Metadata.entity(MaraView.class).property("storedMaker").read());
        var two = assertThrows(IllegalArgumentException.class, () -> Metadata.entity(TwoReads.class));
        assertTrue(two.getMessage().contains("two read templates"), two.getMessage());
        var none = assertThrows(IllegalArgumentException.class, () -> Metadata.entity(NoPlaceholder.class));
        assertTrue(none.getMessage().contains("needs {0}"), none.getMessage());
    }
}
