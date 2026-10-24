import { column, decimal, from, integer, table, text } from "@zerodep-dev/query-core";
import { arrayOf } from "@zerodep-dev/query-postgres";

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
