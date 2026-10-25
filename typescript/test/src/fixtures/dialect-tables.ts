import { bigint, column, date, decimal, from, id, integer, table, text } from "@zerodep-dev/query-core";
import { HanaDialect } from "@zerodep-dev/query-hana";
import { MysqlDialect } from "@zerodep-dev/query-mysql";
import { arrayOf, PostgresDialect } from "@zerodep-dev/query-postgres";

/** 方言专属能力的测试表，与 Java 各方言测试包里的 HanaTables、PgTables、MysqlTables 对应。 */

const SCHEMA = "QUERY_IT";

/** HANA 带输入参数的计算视图（只渲染，HXE 的租户没有 HDI，实库上不建）。 */
@table("CV_SALES", { schema: SCHEMA })
export class SalesView {
  @column("MATNR") material!: string;
  @column("AMOUNT", decimal) amount!: string;
}

/** HANA 表函数：某个单位的交货行。 */
@table("FN_LIPS", { schema: SCHEMA })
export class LipsByUnit {
  @column("VBELN") delivery!: string;
  @column("LFIMG", decimal) quantity!: string;
}

/** HANA 层级测试的组织：用上级编号连成树。 */
@table("H_ORG", { schema: SCHEMA })
export class Org {
  @column("ID") id!: string;
  @column("PARENT_ID") parent!: string | null;
  @column("NAME") name!: string;
}

/** 组织表作为层级的来源：编号与上级编号改名为 nodeId、parentId。 */
export function orgSource() {
  return from(Org).select((o) => ({ nodeId: o.id, parentId: o.parent, name: o.name }));
}

/** PostgreSQL：带标签数组、JSONB 文档和正文的行。 */
@table("PG_TAGGED", { schema: SCHEMA })
export class Tagged {
  @column("ID", integer) id!: number;
  @column("TAGS", arrayOf(text)) tags!: (string | null)[];
  @column("DOC") doc!: string;
  @column("BODY") body!: string;
}

/** MySQL：JSON 文档，FULLTEXT ft_article(TITLE, BODY)。 */
@table("MY_ARTICLE", { schema: SCHEMA })
export class Article {
  @column("ID", integer) id!: number;
  @column("DOC") doc!: string;
  @column("TITLE") title!: string;
  @column("BODY") body!: string;
}

/** PostgreSQL 分区表：按日期范围分区的日志，分区键 DAY 必须在主键里，与 Java 的 PartLog 对应。 */
@table("PART_LOG", { schema: SCHEMA, options: [{ dialect: PostgresDialect, sql: "PARTITION BY RANGE (\"DAY\")" }] })
export class PartLogPg {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: string;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: string | null;
}

/** MySQL 分区表：按日期范围分区的日志，分区键 DAY 必须在主键里，与 Java 的 PartLog 对应。 */
@table("PART_LOG", { schema: SCHEMA, options: [{ dialect: MysqlDialect, sql: "PARTITION BY RANGE COLUMNS(`DAY`) (PARTITION p2025 VALUES LESS THAN ('2026-01-01'), PARTITION p2026 VALUES LESS THAN ('2027-01-01'), PARTITION pmax VALUES LESS THAN (MAXVALUE))" }] })
export class PartLogMysql {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: string;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: string | null;
}

/** PolarDB 的闪回查询与库存提示用的表，与 Java 的 PolardbLiveTest.Stock 对应。 */
@table("P_STOCK", { schema: SCHEMA })
export class PolardbStock {
  @id @column("ID", bigint) id!: bigint;
  @column("QTY", integer) qty!: number;
}

/** HANA 分区表：按日期范围分区的日志，分区键 DAY 必须在主键里，与 Java 的 PartLog 对应。 */
@table("PART_LOG", { schema: SCHEMA, options: [{ dialect: HanaDialect, sql: "PARTITION BY RANGE (DAY) (PARTITION '2025-01-01' <= VALUES < '2026-01-01', PARTITION '2026-01-01' <= VALUES < '2027-01-01', PARTITION OTHERS)" }] })
export class PartLogHana {
  @id @column("ID", bigint) id!: bigint;
  @id @column("DAY", date) day!: string;
  @column("AMOUNT", decimal, { precision: 10, scale: 2 }) amount!: string | null;
}
