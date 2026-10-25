import { column, id, integer, many, manyThrough, one, table, type Many, type One } from "@zerodep-dev/query-core";
import { SCHEMA } from "./erp.js";

/**
 * 用户、角色和用户角色（中间表）：多对多（`@manyThrough`）的对照场景，与 Java 的 Access 一一对应。
 * 实库测试的样例：用户 1 有角色 1（admin）、2（editor），用户 2 有角色 2，用户 3 没有角色。
 */

/** 角色；sortNo 小的排前面。 */
@table("ACC_ROLE", { schema: SCHEMA })
export class Role {
  @id @column("ID", integer) id!: number;
  @column("NAME") name!: string;
  @column("SORT_NO", integer) sortNo!: number;
}

/** 用户与角色的对应，一行一对。 */
@table("ACC_USER_ROLE", { schema: SCHEMA })
export class UserRole {
  @id @column("USER_ID", integer) userId!: number;
  @id @column("ROLE_ID", integer) roleId!: number;

  /** 这一行的角色。 */
  @one(() => Role, (ur, r) => r.id.eq(ur.roleId)) role!: One<Role>;
}

/** 用户。 */
@table("ACC_USER", { schema: SCHEMA })
export class User {
  @id @column("ID", integer) id!: number;
  @column("NAME") name!: string;

  /** 到中间表的一对多。 */
  @many(() => UserRole, (u, ur) => ur.userId.eq(u.id)) userRoles!: Many<UserRole>;

  /** 多对多：经过中间表到角色。 */
  @manyThrough((u) => u.userRoles, (ur) => ur.role) roles!: Many<Role>;
}
