import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { from, where } from "@zerodep-dev/query-core";
import { User } from "../fixtures/access.js";
import { Lips, Mara } from "../fixtures/erp.js";
import { FakeDriver, rows, TestDatabase, TestDialect } from "../fixtures/support.js";

const DIALECT = new TestDialect();

describe("多对多（manyThrough）与子集合上的条件对象", () => {
  it("any 查询中间表，条件里的目标列在子查询里 LEFT JOIN 过去", () => {
    const sql = from(User).where((u) => u.roles.any((r) => r.name.eq("admin"))).select((u) => u.name).toSql(DIALECT).sql;
    assert.ok(
      sql.endsWith(`WHERE EXISTS (SELECT 1
FROM QUERY_IT.ACC_USER_ROLE USERROLE
LEFT JOIN QUERY_IT.ACC_ROLE ROLE ON ROLE.ID = USERROLE.ROLE_ID
WHERE USERROLE.USER_ID = "USER".ID AND ROLE.NAME = ?)`),
      sql,
    );
  });

  it("行数是中间表的行数，用不到目标列时不连接目标表", () => {
    const sql = from(User).select((u) => ({ name: u.name, roles: u.roles.count() })).toSql(DIALECT).sql;
    assert.ok(sql.includes(`(SELECT COUNT(*)\nFROM QUERY_IT.ACC_USER_ROLE USERROLE\nWHERE USERROLE.USER_ID = "USER".ID)`), sql);
    assert.ok(!sql.includes("ACC_ROLE"), sql);
  });

  it("list 用中间表的键批量取子行，投影和排序拿到的是角色", async () => {
    const q = from(User).select((u) => ({ name: u.name, roles: u.roles.orderBy((r) => r.sortNo).list((r) => r.name) }));
    const driver = new FakeDriver((sql) =>
      sql.includes("FROM QUERY_IT.ACC_USER_ROLE") ? rows(["value", "key0"], ["admin", "1"], ["editor", "1"], ["editor", "2"]) : rows(["name", "roles"], ["ann", "1"], ["bob", "2"], ["cy", "3"]),
    );
    assert.deepEqual(await new TestDatabase({}, driver).list(q), [
      { name: "ann", roles: ["admin", "editor"] },
      { name: "bob", roles: ["editor"] },
      { name: "cy", roles: [] },
    ]);
    assert.equal(
      driver.statements[1]!.sql,
      `SELECT ROLE.NAME AS "value", USERROLE.USER_ID AS "key0"
FROM QUERY_IT.ACC_USER_ROLE USERROLE
LEFT JOIN QUERY_IT.ACC_ROLE ROLE ON ROLE.ID = USERROLE.ROLE_ID
WHERE USERROLE.USER_ID IN (?, ?, ?)
ORDER BY ROLE.SORT_NO ASC`,
    );
  });

  it("first 在中间表上排名，再沿导航取目标行的列", () => {
    const sql = from(User).select((u) => ({ name: u.name, top: u.roles.first((r) => r.sortNo.asc()).name })).toSql(DIALECT).sql;
    assert.ok(sql.includes("ROW_NUMBER() OVER (PARTITION BY USERROLE.USER_ID ORDER BY ROLE2.SORT_NO ASC)"), sql);
    assert.ok(sql.endsWith(`USERROLE_FIRST."userId" = "USER".ID AND USERROLE_FIRST."rn__" = 1\nLEFT JOIN QUERY_IT.ACC_ROLE ROLE ON ROLE.ID = USERROLE_FIRST."roleId"`), sql);
  });

  it("条件对象的路径经过多对多时写成 EXISTS", () => {
    class UserSearch {
      @where("eq", "roles.name") role?: string;
    }
    const sql = from(User).where(Object.assign(new UserSearch(), { role: "admin" })).select((u) => u.name).toSql(DIALECT).sql;
    assert.ok(sql.includes("WHERE EXISTS (SELECT 1\nFROM QUERY_IT.ACC_USER_ROLE USERROLE\nLEFT JOIN QUERY_IT.ACC_ROLE ROLE"), sql);
  });

  it("through 写在关系之后，where、orderBy 写在它后面", () => {
    assert.throws(() => from(User).select((u) => u.userRoles.where((ur) => ur.roleId.eq(1)).through((ur) => ur.role).count()), /through\(\.\.\.\) goes right after/);
  });

  it("Many 的 where 也收条件对象：前端的条件能筛子集合", () => {
    const sql = from(Mara)
      .select((m) => ({ material: m.material, kg: m.deliveries.where({ unit: "KG", delivery: " " }).count() }))
      .toSql(DIALECT).sql;
    assert.ok(sql.includes("WHERE LIPS.MATNR = MARA.MATNR AND LIPS.MANDT = MARA.MANDT AND LIPS.VRKME = ?)"), sql);
    assert.throws(() => from(Mara).select((m) => m.deliveries.where({ plant: "1000" }).count()), /the query's tables have no plant/);
    void Lips;
  });
});
