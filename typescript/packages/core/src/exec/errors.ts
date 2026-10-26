/**
 * query 的业务错误码（flow 仓库 docs/decisions/0008）：抛出的错误都带 `code`，HTTP 接口原样写进响应体，前端按它取文案。
 * 与 spec/error-codes.json 里 package 为 core 的条目一致，先后也一样；本地库（idb 包）的码另见 `IDB_ERROR_CODES`。
 *
 *   catch (e) { return { code: (e as { code?: string }).code ?? "app.internal-error", message: String(e) }; }
 */
export const QUERY_ERROR_CODES = [
  "query.request-invalid",
  "query.empty-result",
  "query.too-many-rows",
  "query.unexpected-row-count",
  "query.statement-timeout",
  "query.execution-failed",
] as const;

/** query 核心包的业务错误码 */
export type QueryErrorCode = (typeof QUERY_ERROR_CODES)[number];

/** 表格请求读不懂：页码、条数写错，条件树写错，排序、筛选用了结果里没有的列。说明的是前端传来的参数，可以原样给前端看。 */
export class InvalidRequestError extends Error {
  readonly code = "query.request-invalid";

  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "InvalidRequestError";
  }
}
