/**
 * 核心里 SQLite 不支持的能力：本包用 `never` 类型的同名导出遮住核心的 `grouping` 和 `forUpdate`，
 * 从 `@zerodep-dev/query-sqlite` 导入时写出来就是类型错误；直接从核心导入的仍会在渲染时报错。
 */

/** SQLite 没有 ROLLUP，也就没有 GROUPING()。 */
export const grouping: never = undefined as never;

/** SQLite 只有一个写者，没有 FOR UPDATE；在写事务里读写即可。 */
export const forUpdate: never = undefined as never;
