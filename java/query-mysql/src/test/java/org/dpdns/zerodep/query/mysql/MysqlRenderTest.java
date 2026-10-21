package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.Query;
import org.dpdns.zerodep.query.testing.Erp.Lips;
import org.dpdns.zerodep.query.testing.Scenarios;
import org.dpdns.zerodep.query.testing.TestDialect;
import org.dpdns.zerodep.query.type.CastType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.dpdns.zerodep.query.mysql.Mysql.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MySQL 渲染")
class MysqlRenderTest {

    @Test
    void 标识符一律用反引号() {
        String sql = Scenarios.latestDeliveries().toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.contains("AS `rank`"), "RANK is a reserved word in MySQL 8: " + sql);
        assertTrue(sql.contains("FROM `QUERY_IT`.`LIPS` `lips`"), sql);
    }

    @Test
    void 拼接用CONCAT而不是双竖线() {
        String sql = from(Lips.class).selectValue(l -> concat(l.col(Lips::getMaterial), lit("-"), l.col(Lips::getUnit)))
            .toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT CONCAT(`lips`.`MATNR`, '-', `lips`.`VRKME`)"), sql);
    }

    @Test
    void NULLS_LAST改写为先按是否为空排序() {
        String sql = from(Lips.class).orderBy(l -> l.col(Lips::getCreatedOn).desc().nullsLast())
            .selectValue(l -> l.col(Lips::getDelivery)).toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.endsWith("ORDER BY `lips`.`ERDAT` IS NULL ASC, `lips`.`ERDAT` DESC"), sql);
    }

    @Test
    void 只有OFFSET时补上最大LIMIT() {
        String sql = from(Lips.class).offset(5).selectValue(l -> l.col(Lips::getDelivery)).toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.endsWith("LIMIT 18446744073709551615 OFFSET 5"), sql);
    }

    @Test
    void 字符串字面量转义反斜杠() {
        String sql = from(Lips.class).selectValue(l -> lit("a\\b'c")).toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT 'a\\\\b''c'"), sql);
    }

    @Test
    void 类型转换与优化器提示() {
        Query<Long> q = from(Lips.class).with(maxExecutionTime(1000)).selectValue(l -> cast(l.col(Lips::getQuantity), CastType.bigint()));
        assertEquals("SELECT /*+ MAX_EXECUTION_TIME(1000) */ CAST(`lips`.`LFIMG` AS SIGNED) AS `value`\nFROM `QUERY_IT`.`LIPS` `lips`",
            q.toSql(MysqlDialect.INSTANCE).sql());
        assertThrows(UnsupportedOperationException.class,
            () -> from(Lips.class).selectValue(l -> cast(l.col(Lips::getQuantity), CastType.bool())).toSql(MysqlDialect.INSTANCE));
    }

    @Test
    void MySQL专属函数和子句不能在别的方言上渲染() {
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class)
            .selectValue(l -> groupConcat(l.col(Lips::getDelivery), ",")).toSql(new TestDialect()));
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class)
            .with(maxExecutionTime(10)).selectValue(l -> l.col(Lips::getDelivery)).toSql(new TestDialect()));
    }
}
