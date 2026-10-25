import { column, id, integer, many, table, type Many } from "@zerodep-dev/query-core";
import { SCHEMA } from "./erp.js";

/**
 * 组织单元：每行带父单元的键，树形数据（buildTree、withAncestors、懒加载的 hasChildren）的对照场景，与 Java 的 Org 一一对应。
 * 实库测试的样例：1 研发（根）下有 2 前端、3 后端，3 后端下有 4 平台；5 采购是另一个根。
 */
@table("ORG_UNIT", { schema: SCHEMA })
export class OrgUnit {
  @id @column("ID", integer) id!: number;
  @column("PARENT_ID", integer) parentId!: number | null;
  @column("NAME") name!: string;

  /** 下一级单元。 */
  @many(() => OrgUnit, (u, c) => c.parentId.eq(u.id)) children!: Many<OrgUnit>;
}
