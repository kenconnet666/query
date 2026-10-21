package org.dpdns.zerodep.query.meta;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.testing.Erp;
import org.dpdns.zerodep.query.testing.Erp.LineVO;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Erp.Mardh;
import org.dpdns.zerodep.query.testing.Erp.MaterialScope;
import org.dpdns.zerodep.query.testing.Erp.PeriodStock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.dpdns.zerodep.query.Sql.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("元数据")
class MetadataTest {

    @Test
    void Lombok的getter解析到属性和列() {
        PropertyMeta p = Metadata.property(Lips.class, Lips::getMaterial);
        assertEquals("material", p.name());
        assertEquals("MATNR", p.columnName(NamingStrategy.AS_IS));
    }

    @Test
    void 基类声明的getter按子类实体解析() {
        PropertyMeta p = Metadata.property(Lips.class, Erp.ClientEntity::getClient);
        assertEquals(Lips.class, p.entity(), "the property belongs to the entity it was resolved on");
        assertEquals("MANDT", p.column());
        assertEquals("client", Metadata.entity(Lips.class).properties().get(0).name(), "base class fields come first");
    }

    @Test
    void 接口getter按属性名在实体上解析() {
        assertEquals("LFGJA", Metadata.property(Mardh.class, PeriodStock::getYear).column());
    }

    @Test
    void record访问器就是属性名() {
        assertEquals("partNumber", Metadata.property(MaterialScope.class, MaterialScope::partNumber).name());
    }

    @Test
    void 组合注解挂载转换器() {
        PropertyMeta p = Metadata.property(Lips.class, Lips::getCreatedOn);
        assertEquals(LocalDate.class, p.javaType());
        assertEquals(String.class, p.type().storageType());
        @SuppressWarnings("unchecked")
        var type = (org.dpdns.zerodep.query.type.SqlType<LocalDate>) p.type();
        assertEquals("20260131", type.toDatabase(LocalDate.of(2026, 1, 31)));
        assertEquals(null, type.fromDatabase("00000000"));
    }

    @Test
    void 不是方法引用的lambda被拒绝() {
        Getter<Lips, String> lambda = l -> l.getMaterial();
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.propertyName(lambda));
        assertTrue(e.getMessage().contains("method reference"), e.getMessage());
    }

    @Test
    void getter返回类型和字段类型不一致时报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.property(Odd.class, Odd::getCode));
        assertTrue(e.getMessage().contains("returns Integer"), e.getMessage());
    }

    @Test
    void 转换器类型和字段类型不一致时扫描报错() {
        var e = assertThrows(IllegalArgumentException.class, () -> Metadata.entity(WrongConverter.class));
        assertTrue(e.getMessage().contains("converts LocalDate, but the field is String"), e.getMessage());
    }

    @Test
    void 没有Table注解的类不能当作表() {
        var e = assertThrows(IllegalArgumentException.class, () -> from(MaterialScope.class));
        assertTrue(e.getMessage().contains("has no @Table"), e.getMessage());
    }

    @Test
    void Ignore字段不是属性() {
        assertFalse(Metadata.entity(LineVO.class).has("computedLater"));
    }

    @Test
    void 命名策略推导列名() {
        assertEquals("PART_NUMBER", NamingStrategy.UPPER_SNAKE.column("partNumber"));
        assertEquals("part_number", NamingStrategy.LOWER_SNAKE.column("partNumber"));
    }

    static final class Odd {
        private String code;

        public Integer getCode() { return code == null ? null : code.length(); }
    }

    static final class WrongConverter {
        @Column(value = "ERDAT", converter = Erp.SapDateConverter.class) private String createdOn;
    }
}
