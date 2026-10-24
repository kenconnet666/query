package org.dpdns.zerodep.query.postgres;

import org.dpdns.zerodep.query.dialect.Rendered;
import org.dpdns.zerodep.query.exec.ConnectionProvider;
import org.dpdns.zerodep.query.exec.Database;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.write.Delete;
import org.dpdns.zerodep.query.write.Insert;
import org.dpdns.zerodep.query.write.Update;
import org.dpdns.zerodep.query.write.Write;

import java.util.List;
import java.util.StringJoiner;
import java.util.function.Consumer;

/** PostgreSQL 数据库实例；通过 {@link Postgres#database} 创建。 */
public final class PostgresDatabase extends Database {
    /** 仅由 {@link Postgres#database} 调用；{@code configure} 可为 null。 */
    PostgresDatabase(ConnectionProvider connections, Consumer<Config> configure) {
        super(PostgresDialect.INSTANCE, connections, configure);
    }

    /**
     * 执行插入、更新或删除，并用 {@code RETURNING} 读回改到的行：shape 的每个属性对应表里的同名属性（列），
     * 例如 {@code db.returning(update(Doc.class).set(...).where(...), DocRow.class)}。逻辑删除改写成的 UPDATE 同样适用；
     * 联表更新和删除不支持（列名可能在两张表里重复）。
     */
    public <R> List<R> returning(Write write, Class<R> shape) {
        Class<?> table;
        if (write instanceof Insert<?> i) table = i.table();
        else if (write instanceof Update<?> u) table = u.table();
        else if (write instanceof Delete<?> d) table = d.table();
        else throw new IllegalArgumentException("returning(...) takes an insert, update or delete, not " + write.getClass().getSimpleName());
        EntityMeta<?> meta = Metadata.entity(table);
        StringJoiner columns = new StringJoiner(", ", " RETURNING ", "");
        for (PropertyMeta p : Metadata.entity(shape).properties()) {
            if (!meta.has(p.name())) throw new IllegalArgumentException(shape.getSimpleName() + "." + p.name() + " has no column in " + table.getSimpleName());
            columns.add(dialect().quote(meta.property(p.name()).columnName(naming())) + " AS " + dialect().quote(p.name()));
        }
        String suffix = columns.toString();
        return writeReturning(render(write).stream().map(s -> new Rendered(s.sql() + suffix, s.bindings(), s.dialect())).toList(), shape);
    }
}
