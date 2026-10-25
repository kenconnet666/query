package org.dpdns.zerodep.query.mysql;

import org.dpdns.zerodep.query.select.Query;
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
        String sql = from(Lips.class).select(l -> l.material().concat(lit("-"), l.unit()))
            .toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT CONCAT(`lips`.`MATNR`, '-', `lips`.`VRKME`)"), sql);
    }

    @Test
    void NULLS_LAST改写为先按是否为空排序() {
        String sql = from(Lips.class).orderBy(l -> l.createdOn().desc().nullsLast())
            .select(l -> l.delivery()).toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.endsWith("ORDER BY `lips`.`ERDAT` IS NULL ASC, `lips`.`ERDAT` DESC"), sql);
    }

    @Test
    void 只有OFFSET时补上最大LIMIT() {
        String sql = from(Lips.class).offset(5).select(l -> l.delivery()).toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.endsWith("LIMIT 18446744073709551615 OFFSET 5"), sql);
    }

    @Test
    void 字符串字面量转义反斜杠() {
        String sql = from(Lips.class).select(l -> lit("a\\b'c")).toSql(MysqlDialect.INSTANCE).sql();
        assertTrue(sql.startsWith("SELECT 'a\\\\b''c'"), sql);
    }

    @Test
    void 类型转换与优化器提示() {
        Query<Long> q = from(Lips.class).with(maxExecutionTime(1000)).select(l -> l.quantity().cast(CastType.bigint()));
        assertEquals("SELECT /*+ MAX_EXECUTION_TIME(1000) */ CAST(`lips`.`LFIMG` AS SIGNED) AS `value`\nFROM `QUERY_IT`.`LIPS` `lips`",
            q.toSql(MysqlDialect.INSTANCE).sql());
        assertThrows(UnsupportedOperationException.class,
            () -> from(Lips.class).select(l -> l.quantity().cast(CastType.bool())).toSql(MysqlDialect.INSTANCE));
    }

    @Test
    void MySQL专属函数和子句不能在别的方言上渲染() {
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class)
            .select(l -> mysql(l.createdOn()).dateFormat("%Y")).toSql(new TestDialect()));
        assertThrows(UnsupportedOperationException.class, () -> from(Lips.class)
            .with(maxExecutionTime(10)).select(l -> l.delivery()).toSql(new TestDialect()));
    }

    @Test
    void 按声明建表的语句() {
        java.util.List<String> statements = Mysql.database(org.dpdns.zerodep.query.exec.ConnectionProvider.dryRun(), null).schema()
            .createTable(org.dpdns.zerodep.query.testing.Schemas.SItem.class);
        assertEquals(java.util.List.of(
            "CREATE TABLE `QUERY_IT`.`S_ITEM` (`ID` BIGINT NOT NULL AUTO_INCREMENT, `CODE` VARCHAR(20) NOT NULL, `CREATED_ON` DATE, `OWNER_ID` BIGINT, `PRICE` DECIMAL(13,3), `STATUS` VARCHAR(10) NOT NULL DEFAULT 'open', `TITLE` VARCHAR(40) COMMENT 'title', PRIMARY KEY (`ID`)) COMMENT='items'",
            "CREATE UNIQUE INDEX `IX_S_ITEM_CODE` ON `QUERY_IT`.`S_ITEM` (`CODE`)",
            "ALTER TABLE `QUERY_IT`.`S_ITEM` ADD CONSTRAINT `FK_S_ITEM_OWNER` FOREIGN KEY (`OWNER_ID`) REFERENCES `QUERY_IT`.`S_OWNER` (`ID`)"), statements);
    }
}
