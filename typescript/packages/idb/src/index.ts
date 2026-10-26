/**
 * `@zerodep-dev/idb`：给前端用的轻量 IndexedDB 本地存储（见 docs/decisions/0010）。用装饰器声明仓库和索引，
 * 只按索引查询，事务在回调 await 了别的工作时给出明确的报错。不依赖 `@zerodep-dev/query-core`，不做同步。
 */
export { bigint, boolean, index, key, lowercase, many, one, store, type Codec, type IndexOptions, type StoreClass, type StoreOptions } from "./metadata.js";
export { IDB_ERROR_CODES, type IdbErrorCode } from "./errors.js";
export { InvalidRowError } from "./rows.js";
export { BatchWriteError, QuotaError, Transaction, TransactionClosedError } from "./transaction.js";
export { Query, Source, Where, type BetweenOptions } from "./query.js";
export { LocalDatabase, openLocal, type OpenOptions, type UpgradeStep } from "./database.js";
