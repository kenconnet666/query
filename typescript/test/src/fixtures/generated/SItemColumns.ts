import { bigint, column, date, decimal, generated, id, text } from "@zerodep-dev/query-core";

/**
 * QUERY_IT.S_ITEM（items）的列，由 query-pull 从数据库生成；重新生成会覆盖这个文件，不要手改。
 * 在自己的表类里继承它：`@table("S_ITEM", { schema: "QUERY_IT" }) export class X extends SItemColumns { ... }`。
 */
export class SItemColumns {
  /** ID：BIGINT，主键，数据库生成 */
  @id @generated @column("ID", bigint) id!: bigint;

  /** CODE：TEXT(20)，NOT NULL */
  @column("CODE", text, { length: 20, nullable: false }) code!: string;

  /** CREATED_ON：DATE */
  @column("CREATED_ON", date) createdOn!: string | null;

  /** OWNER_ID：BIGINT */
  @column("OWNER_ID", bigint) ownerId!: bigint | null;

  /** PRICE：DECIMAL(13,3) */
  @column("PRICE", decimal, { precision: 13, scale: 3 }) price!: string | null;

  /** STATUS：TEXT(10)，NOT NULL */
  @column("STATUS", text, { length: 10, nullable: false, default: "'open'" }) status!: string;

  /** TITLE：TEXT(40)。title */
  @column("TITLE", text, { length: 40, comment: "title" }) title!: string | null;
}
