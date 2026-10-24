package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.testing.Schemas;
import org.dpdns.zerodep.query.testing.generated.SItemColumns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("query:pull 生成的源码")
class SourceGeneratorTest {
    /** 继承生成的父接口，只补上表名。 */
    @Table(schema = "QUERY_IT", value = "S_ITEM", comment = "items")
    interface PulledItem extends SItemColumns { }

    @Test
    void 列名和表名转成Java名称() {
        assertEquals("orderId", SourceGenerator.propertyName("ORDER_ID"));
        assertEquals("matnr", SourceGenerator.propertyName("MATNR"));
        assertEquals("classColumn", SourceGenerator.propertyName("CLASS"));
        assertEquals("c1x", SourceGenerator.propertyName("1X"));
        assertEquals("SItemColumns", SourceGenerator.typeName("S_ITEM"));
        assertEquals("ClassColumns", SourceGenerator.typeName("CLASS"));
    }

    @Test
    void 继承生成的父接口得到与手写声明相同的列() {
        TableDef pulled = Declarations.of(PulledItem.class, NamingStrategy.AS_IS, UnaryOperator.identity());
        TableDef written = Declarations.of(Schemas.SItem.class, NamingStrategy.AS_IS, UnaryOperator.identity());
        assertEquals(written.columns(), pulled.columns());
        assertEquals(written.primaryKey(), pulled.primaryKey());
    }

    @Test
    void 白名单里的列不存在时报错() {
        TableDef table = TableBuilder.table("", "T").column("A", ColumnType.text(10)).build();
        assertThrows(IllegalArgumentException.class, () -> SourceGenerator.java(table, "p", "TColumns", List.of("B")));
    }
}
