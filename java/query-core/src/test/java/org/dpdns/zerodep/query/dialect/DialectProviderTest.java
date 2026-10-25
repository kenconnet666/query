package org.dpdns.zerodep.query.dialect;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通用门面选方言的规则；各库实际识别出的方言在 query-it 的 PortableFacadeTest 里验证。 */
class DialectProviderTest {
    @Test
    void 没有方言认识连接的产品名时报错并归还连接() {
        // 核心包的测试类路径上没有方言包，所以谁也不认识；读产品名借的连接要归还。
        int[] released = {0};
        ConnectionProvider oracle = new ConnectionProvider() {
            @Override public Connection acquire() { return connection("Oracle"); }

            @Override public void release(Connection connection) { released[0]++; }
        };
        var e = assertThrows(IllegalStateException.class, () -> Sql.database(oracle, null));
        assertTrue(e.getMessage().contains("'Oracle'") && e.getMessage().contains("registered: []"), e.getMessage());
        assertEquals(1, released[0]);
    }

    /** 只回答产品名的连接。 */
    private static Connection connection(String product) {
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(DialectProviderTest.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
            (proxy, method, args) -> method.getName().equals("getDatabaseProductName") ? product : null);
        return (Connection) Proxy.newProxyInstance(DialectProviderTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> method.getName().equals("getMetaData") ? metadata : null);
    }
}
