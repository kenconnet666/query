package org.dpdns.zerodep.query;

/**
 * Base of every table interface. A table is an interface annotated with {@code @Table} whose
 * abstract methods returning {@code Col<T>} are its columns; default methods hold reusable logic
 * such as computed columns. The library implements the interface once per table occurrence in a
 * query, so {@code l.material()} is that occurrence's column:
 * <pre>{@code
 * @Table(schema = "ERP", value = "LIPS")
 * public interface Lips extends Entity {
 *     @Column("MATNR") Col<String> material();
 *     @Column("LFIMG") Col<BigDecimal> quantity();
 *     default Expr<BigDecimal> doubled() { return times(quantity(), lit(2)); }
 * }
 * }</pre>
 * Shared columns go into base interfaces (without {@code @Table}) that tables extend; a table
 * redeclaring a column method with its own {@code @Column} renames that column.
 */
public interface Entity {
    /** Every column of this table, by method name, for {@code select(l -> cols(l.all(), ...))}. */
    Cols all();
}
