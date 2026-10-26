/**
 * 本地库的业务错误码（flow 仓库 docs/decisions/0008）：抛出的错误都带 `code`，前端按它取文案。
 * 与 spec/error-codes.json 里 package 为 idb 的条目一致，先后也一样；与核心包的 `QUERY_ERROR_CODES` 同属 `query.`。
 */
export const IDB_ERROR_CODES = ["query.invalid-row", "query.batch-write-failed", "query.transaction-closed", "query.quota-exceeded"] as const;

/** 本地库的业务错误码 */
export type IdbErrorCode = (typeof IDB_ERROR_CODES)[number];
