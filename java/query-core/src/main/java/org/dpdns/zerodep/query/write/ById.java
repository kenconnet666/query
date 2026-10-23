package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code byId(row)} 的行：按 {@code @Id} 的列（有 {@code @Version} 时加上版本）定位每一行，更新时 SET 其余的属性。
 *
 * @param rows    要定位的行，类型相同
 * @param columns 行类的属性对应的列
 * @param writes  表上的主键、版本和生成列
 */
record ById(List<Object> rows, List<RowColumns.Column> columns, WriteMeta writes) {
    /** 检查表有主键、行类带有每个主键属性（和版本属性）；statement 用于报错。 */
    static ById of(EntityMeta<?> table, List<?> rows, String statement) {
        WriteMeta writes = table.writes();
        if (writes.ids().isEmpty()) throw new IllegalArgumentException(statement + " needs @Id columns on " + table.type().getSimpleName());
        if (rows.isEmpty()) throw new IllegalArgumentException(statement + " needs at least one row");
        Class<?> type = RowColumns.sameClass(rows, statement);
        List<RowColumns.Column> columns = RowColumns.of(table, type);
        Set<String> names = columns.stream().map(RowColumns.Column::name).collect(Collectors.toSet());
        for (String id : writes.ids()) {
            if (!names.contains(id)) throw new IllegalArgumentException(type.getSimpleName() + " needs the key property '" + id + "' for " + statement);
        }
        if (writes.version() != null && !names.contains(writes.version())) {
            throw new IllegalArgumentException(type.getSimpleName() + " needs the version property '" + writes.version() + "' for " + statement);
        }
        return new ById(List.copyOf(rows), columns, writes);
    }

    /** 每行影响一行。 */
    Long expected() { return (long) rows.size(); }

    /** 一行的定位条件：主键相等，有版本列时版本也相等；主键或版本为 null 时报错。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Cond locate(Ref<?> ref, Object row) {
        List<Cond> conds = new ArrayList<>();
        for (RowColumns.Column c : columns) {
            boolean key = writes.ids().contains(c.name()) || c.name().equals(writes.version());
            if (!key) continue;
            Object value = c.value(row);
            if (value == null) throw new IllegalArgumentException(row.getClass().getSimpleName() + "." + c.name() + " is null; byId(...) needs it");
            conds.add(((Col) new Col<>(ref, c.column())).eq(value));
        }
        return Sql.all(conds);
    }

    /** 一行的 SET 项：主键、版本和生成列以外的属性；有版本列时加上 {@code version = version + 1}。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Assignment> sets(Ref<?> ref, Object row) {
        List<Assignment> out = new ArrayList<>();
        for (RowColumns.Column c : columns) {
            if (writes.ids().contains(c.name()) || c.name().equals(writes.version()) || c.name().equals(writes.generated())) continue;
            out.add(((Col) new Col<>(ref, c.column())).to(c.value(row)));
        }
        if (writes.version() != null) {
            Col version = new Col<>(ref, columns.stream().filter(c -> c.name().equals(writes.version())).findFirst().orElseThrow().column());
            out.add(version.to((Expr) Sql.plus(version, Sql.lit(1))));
        }
        return out;
    }
}
