package org.dpdns.zerodep.query.polardb;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.table.Source;
import org.dpdns.zerodep.query.type.SqlType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** PolarDB 专属的数据来源：闪回查询。由 {@link Polardb} 的工厂方法创建。 */
final class PolardbSources {
    /** 只放嵌套类。 */
    private PolardbSources() { }

    /** 渲染目标不是 PolarDB 时抛出异常。 */
    static void requirePolardb(SqlWriter w) {
        if (!(w.dialect() instanceof PolardbDialect)) throw new UnsupportedOperationException("A PolarDB-only operation cannot run on " + w.dialect().name());
    }

    /**
     * 表在某个时刻的样子：{@code 表 AS OF TIMESTAMP CAST(? AS DATETIME(6))}，别名跟在后面。
     * 时刻要求是时间类型（实测直接绑参数报 7502），所以绑定成 {@code yyyy-MM-dd HH:mm:ss[.小数]} 的文本再 CAST；
     * 与 TypeScript 规范化后的时间写法相同，两端的金样例因此一致。
     */
    static final class AsOf<E> implements Source<E> {
        private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

        private final EntityMeta<E> meta;
        private final String at;

        AsOf(Class<E> table, LocalDateTime at) {
            this.meta = Metadata.entity(table);
            if (!meta.isTable()) throw new IllegalArgumentException(table.getSimpleName() + " has no @Table naming the table to read back");
            this.at = text(Objects.requireNonNull(at, "at"));
        }

        /** 秒以下的部分去掉末尾的 0，没有时不写小数点。 */
        private static String text(LocalDateTime at) {
            String fraction = String.format("%09d", at.getNano()).replaceAll("0+$", "");
            return at.format(SECONDS) + (fraction.isEmpty() ? "" : "." + fraction);
        }

        @Override public Class<E> entity() { return meta.type(); }

        @Override public void renderSource(SqlWriter w) {
            requirePolardb(w);
            w.table(meta.schema(), meta.table());
            w.sql(" AS OF TIMESTAMP CAST(").render(new Nodes.Param<>(SqlType.of(String.class), at)).sql(" AS DATETIME(6))");
        }

        @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.columnName(naming); }

        /** 和表一样套用数据库实例的全局过滤器与逻辑删除条件。 */
        @Override public boolean filtered() { return true; }
    }
}
