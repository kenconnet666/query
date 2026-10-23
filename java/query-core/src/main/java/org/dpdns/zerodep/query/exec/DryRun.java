package org.dpdns.zerodep.query.exec;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * {@link ConnectionProvider#dryRun()} 的连接：不访问任何数据库，每条查询都得到空结果。
 * 用 JDK 动态代理实现 JDBC 接口，其余方法什么也不做，返回 0、false 或 null。
 */
final class DryRun {
    /** 工具类，不能实例化。 */
    private DryRun() { }

    /** 每次给出一个新的空跑连接。 */
    static ConnectionProvider provider() { return new Provider(); }

    /** 空跑的连接来源；{@link #is} 用它的类型识别。 */
    private static final class Provider implements ConnectionProvider {
        @Override public Connection acquire() { return connection(); }

        @Override public void release(Connection connection) { }
    }

    /** connections 是否是空跑的连接来源。 */
    static boolean is(ConnectionProvider connections) { return connections instanceof Provider; }

    /** 准备语句时返回空跑的语句；自动提交为开，PostgreSQL 的游标准备因此照常执行。 */
    private static Connection connection() {
        return proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement", "prepareCall" -> statement();
            case "createStatement" -> proxy(Statement.class, (m, a) -> m.equals("executeQuery") ? resultSet() : null);
            case "getAutoCommit" -> true;
            default -> null;
        });
    }

    /** 执行查询时返回没有行的结果集；写语句影响 0 行。 */
    private static PreparedStatement statement() {
        int[] batched = {0};
        return proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "executeQuery" -> resultSet();
            case "addBatch" -> {
                batched[0]++;
                yield null;
            }
            case "executeBatch" -> new int[batched[0]];
            default -> null;
        });
    }

    /** 没有行、也没有列的结果集。 */
    private static ResultSet resultSet() {
        return proxy(ResultSet.class, (method, args) -> method.equals("getMetaData") ? proxy(ResultSetMetaData.class, (m, a) -> null) : null);
    }

    /** 方法的处理：按方法名返回结果，null 表示用默认值。 */
    @FunctionalInterface
    private interface Answer {
        /** 返回方法的结果；null 时按返回类型给出 0、false 或 null。 */
        Object answer(String method, Object[] args);
    }

    /** 实现接口 type：answer 给出结果，未给出的按返回类型取默认值。 */
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(DryRun.class.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            switch (method.getName()) {
                case "equals": return self == args[0];
                case "hashCode": return System.identityHashCode(self);
                case "toString": return "dry-run " + type.getSimpleName();
                default: break;
            }
            Object result = answer.answer(method.getName(), args);
            if (result != null) return result;
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == double.class) return 0.0;
            if (r == float.class) return 0.0f;
            if (r == short.class) return (short) 0;
            if (r == byte.class) return (byte) 0;
            return null;
        }));
    }
}
