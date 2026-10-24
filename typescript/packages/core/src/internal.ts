/** 包内部能力的键，让各模块可以互相调用而不产生循环导入。 */

/** `query[SUBQUERY](w)` 把查询渲染成值：EXISTS、IN 或标量子查询。 */
export const SUBQUERY = Symbol("query.subquery");

/** `query[NAMED](w)` 渲染列名有意义的查询：CTE 主体或派生表。 */
export const NAMED = Symbol("query.named");

/** `query[COLUMN_TYPES]` 列出查询各列的类型。 */
export const COLUMN_TYPES = Symbol("query.columnTypes");

/** `query[COLUMNS]` 给可用作 CTE 或派生表的查询的列命名并标明类型。 */
export const COLUMNS = Symbol("query.columns");
