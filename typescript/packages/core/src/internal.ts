/** Keys of package-internal capabilities, so modules can call each other without import cycles. */

/** `query[SUBQUERY](w)` renders a query used as a value: EXISTS, IN or a scalar subquery. */
export const SUBQUERY = Symbol("query.subquery");

/** `query[NAMED](w)` renders a query whose column names matter: a CTE body or a derived table. */
export const NAMED = Symbol("query.named");

/** `query[COLUMN_TYPES]` lists the types of the query's columns. */
export const COLUMN_TYPES = Symbol("query.columnTypes");

/** `query[COLUMNS]` names and types the columns of a query usable as a CTE or derived table. */
export const COLUMNS = Symbol("query.columns");
