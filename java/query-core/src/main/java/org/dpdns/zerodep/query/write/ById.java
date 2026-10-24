package org.dpdns.zerodep.query.write;

import org.dpdns.zerodep.query.Sql;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Expr;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.WriteMeta;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Ref;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code byId(row)} 的行：按 {@code @Id} 的列（有 {@code @Version} 时加上版本）定位每一行，更新时 SET 其余的属性。
 *
 * @param rows      要定位的行，类型相同
 * @param columns   行类的属性对应的列
 * @param writes    表上的主键、版本、生成列和写入策略
 * @param originals {@code diffFrom(...)} 给出的修改前的行，与 rows 一一对应；没有时为 null，SET 全部属性
 */
record ById(List<Object> rows, List<RowColumns.Column> columns, WriteMeta writes, List<Object> originals) {
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
        return new ById(List.copyOf(rows), columns, writes, null);
    }

    /** 带上修改前的行：数量和类与 rows 相同，主键（和版本）相等；之后只 SET 值变了的属性。 */
    ById diffFrom(List<?> before) {
        if (before.size() != rows.size()) {
            throw new IllegalArgumentException("diffFrom(...) needs one original per byId(...) row: got " + before.size() + " for " + rows.size());
        }
        RowColumns.sameClass(before, "diffFrom(...)");
        if (before.get(0).getClass() != rows.get(0).getClass()) {
            throw new IllegalArgumentException("diffFrom(...) rows must be " + rows.get(0).getClass().getSimpleName() + ", got "
                + before.get(0).getClass().getSimpleName());
        }
        for (int i = 0; i < rows.size(); i++) {
            for (RowColumns.Column c : columns) {
                if (locates(c) && !Objects.equals(c.value(rows.get(i)), c.value(before.get(i)))) {
                    throw new IllegalArgumentException("diffFrom(...) row " + i + " has a different " + c.name() + "; byId(...) cannot change the key or version");
                }
            }
        }
        return new ById(rows, columns, writes, List.copyOf(before));
    }

    /** 每行影响一行；有 diffFrom 时只算有变化的行。 */
    Long expected() {
        if (originals == null) return (long) rows.size();
        long changed = 0;
        for (int i = 0; i < rows.size(); i++) if (changed(i)) changed++;
        return changed;
    }

    /** 一行的定位条件：主键相等，有版本列时版本也相等；主键或版本为 null 时报错。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Cond locate(Ref<?> ref, Object row) {
        List<Cond> conds = new ArrayList<>();
        for (RowColumns.Column c : columns) {
            if (!locates(c)) continue;
            Object value = c.value(row);
            if (value == null) throw new IllegalArgumentException(row.getClass().getSimpleName() + "." + c.name() + " is null; byId(...) needs it");
            conds.add(((Col) new Col<>(ref, c.column())).eq(value));
        }
        return Sql.all(conds);
    }

    /**
     * 第 i 行的 SET 项：可更新的属性（不含主键、版本、生成列、只读和只在插入时写的列），有 diffFrom 时只含值变了的；
     * 有 SET 项且表上有版本列时加上 {@code version = version + 1}。没有变化时为空，这一行不发语句。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    List<Assignment> sets(Ref<?> ref, int i) {
        Object row = rows.get(i);
        List<Assignment> out = new ArrayList<>();
        for (RowColumns.Column c : columns) {
            if (!writes.updates(c.name())) continue;
            if (originals != null && same(c.value(row), c.value(originals.get(i)))) continue;
            out.add(((Col) new Col<>(ref, c.column())).to(c.value(row)));
        }
        if (originals != null && out.isEmpty()) return out;
        if (writes.version() != null) {
            Col version = new Col<>(ref, columns.stream().filter(c -> c.name().equals(writes.version())).findFirst().orElseThrow().column());
            out.add(version.to((Expr) Sql.plus(version, Sql.lit(1))));
        }
        return out;
    }

    /** 第 i 行与修改前相比有没有变化；没有 diffFrom 时总是有。 */
    boolean changed(int i) {
        if (originals == null) return true;
        for (RowColumns.Column c : columns) {
            if (writes.updates(c.name()) && !same(c.value(rows.get(i)), c.value(originals.get(i)))) return true;
        }
        return false;
    }

    /** 这一列是否参与定位：主键和版本。 */
    private boolean locates(RowColumns.Column c) { return writes.ids().contains(c.name()) || c.name().equals(writes.version()); }

    /** 两个值是否相同；BigDecimal 按数值比较，1.0 与 1.00 相同。 */
    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(a, b);
    }
}
