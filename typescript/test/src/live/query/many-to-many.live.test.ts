import assert from "node:assert/strict";
import { before, it } from "node:test";
import { bool, from, insertInto, where } from "@zerodep-dev/query-core";
import { Role, User, UserRole } from "../../fixtures/access.js";
import { liveSuite } from "../support/live.js";

/** 按角色名找用户：路径经过多对多。 */
class UserSearch {
  @where("eq", "roles.name") role?: string;
}

/**
 * 多对多（`@manyThrough`）：经过中间表的 any、count、list、first 和条件对象。每个库建一次表，写入（与 Java 的 ManyToManyTest 相同）：
 *
 * ```
 * ACC_USER 用户     ACC_ROLE 角色          ACC_USER_ROLE
 *   1  ann            1  admin   次序 1       1 → 1、1 → 2
 *   2  bob            2  editor  次序 2       2 → 2
 *   3  cy                                     （cy 没有角色）
 * ```
 */
liveSuite("多对多", (live) => {
  before(async () => {
    await live.recreate(Role, UserRole, User);
    const db = live.database();
    await db.execute(insertInto(Role).values({ id: 1, name: "admin", sortNo: 1 }, { id: 2, name: "editor", sortNo: 2 }));
    await db.execute(insertInto(User).values({ id: 1, name: "ann" }, { id: 2, name: "bob" }, { id: 3, name: "cy" }));
    await db.execute(insertInto(UserRole).values({ userId: 1, roleId: 1 }, { userId: 1, roleId: 2 }, { userId: 2, roleId: 2 }));
  });

  it("any、count 查询中间表，条件里的角色列在子查询里连接过去", async () => {
    const admins = await live.db.list(from(User).where((u) => u.roles.any((r) => r.name.eq("admin"))).select((u) => u.name));
    assert.deepEqual(admins, ["ann"]);
    const counts = await live.db.list(from(User).orderBy((u) => u.id).select((u) => ({ name: u.name, roles: u.roles.count(), hasRoles: bool(() => u.roles.any()) })));
    assert.deepEqual(counts, [
      { name: "ann", roles: 2, hasRoles: true },
      { name: "bob", roles: 1, hasRoles: true },
      { name: "cy", roles: 0, hasRoles: false },
    ]);
  });

  it("list 按中间表的键批量取角色；first 取次序最小的角色", async () => {
    const rows = await live.db.list(
      from(User)
        .orderBy((u) => u.id)
        .select((u) => ({ name: u.name, roles: u.roles.orderBy((r) => r.sortNo).list((r) => r.name), top: u.roles.first((r) => r.sortNo.asc()).name })),
    );
    assert.deepEqual(rows, [
      { name: "ann", roles: ["admin", "editor"], top: "admin" },
      { name: "bob", roles: ["editor"], top: "editor" },
      { name: "cy", roles: [], top: null },
    ]);
  });

  it("条件对象的路径经过多对多", async () => {
    const editors = await live.db.list(from(User).where(Object.assign(new UserSearch(), { role: "editor" })).orderBy((u) => u.id).select((u) => u.name));
    assert.deepEqual(editors, ["ann", "bob"]);
  });
});
