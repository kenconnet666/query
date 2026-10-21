import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { cast, CastType, date, from, lit, sum } from "@query/core";
import { addDays, cube, daysBetween, groupingSets, HanaDatabase, hint } from "@query/hana";
import { groupConcat, maxExecutionTime, MysqlDatabase } from "@query/mysql";
import { cube as pgCube, distinctOn, filterWhere, groupingSets as pgGroupingSets, ilike, PostgresDatabase } from "@query/postgres";
import { Lips } from "../erp.js";
import { deliveries } from "../scenarios.js";
import { TestDialect } from "../support.js";

const never = { query: () => Promise.reject(new Error("never connects")) };
const hana = new HanaDatabase(never);
const postgres = new PostgresDatabase(never);
const mysql = new MysqlDatabase(never);
const other = new TestDialect();

describe("方言", () => {
  it("PostgreSQL 的参数按 CTE 提升后的最终顺序编号", () => {
    const r = postgres.render(deliveries("2026-01-01", "2026-06-30", "MK1"));
    assert.deepEqual(r.sql.match(/\$\d+/g), ["$1", "$2", "$3", "$4", "$5"]);
    assert.deepEqual(r.parameters, ["ZMAT", "MK1", "1", "20260101", "20260630"]);
  });

  it("HANA 与 MySQL 用问号占位", () => {
    assert.equal(hana.render(deliveries("2026-01-01", "2026-06-30", null)).sql.match(/\?/g)?.length, 4);
    assert.equal(mysql.render(deliveries("2026-01-01", "2026-06-30", null)).sql.match(/\?/g)?.length, 4);
  });

  it("DISTINCT ON 替代 DISTINCT 并放在 SELECT 之后", () => {
    const s = postgres.render(
      from(Lips)
        .with((l) => distinctOn(l.material))
        .distinct()
        .orderBy((l) => [l.material.asc(), l.createdOn.desc()])
        .selectValue((l) => l.delivery),
    ).sql;
    assert.ok(s.startsWith('SELECT DISTINCT ON (lips."MATNR") lips."VBELN" AS delivery'), s);
  });

  it("MySQL 没有到 BOOLEAN 的类型转换", () => {
    assert.throws(() => mysql.render(from(Lips).selectValue((l) => cast(l.quantity, CastType.boolean()))), /no CAST to BOOLEAN/);
  });

  it("方言专属函数和子句不能在别的方言上渲染", () => {
    const day = lit("2026-01-01", date);
    const cases = [
      from(Lips).selectValue(() => daysBetween(day, addDays(day, 1))),
      from(Lips)
        .with(hint("NO_CS_JOIN"))
        .selectValue((l) => l.delivery),
      from(Lips).selectValue((l) => groupConcat(l.delivery, ",")),
      from(Lips)
        .with(maxExecutionTime(10))
        .selectValue((l) => l.delivery),
      from(Lips)
        .where((l) => ilike(l.unit, "ea"))
        .selectValue((l) => l.delivery),
      from(Lips).selectValue((l) => filterWhere(sum(l.quantity), l.material.eq("M1"))),
      from(Lips)
        .with((l) => distinctOn(l.material))
        .selectValue((l) => l.delivery),
      from(Lips)
        .groupBy((l) => cube(l.material))
        .selectValue(() => sum(lit(1))),
      from(Lips)
        .groupBy((l) => groupingSets([l.material], []))
        .selectValue(() => sum(lit(1))),
      from(Lips)
        .groupBy((l) => pgCube(l.material))
        .selectValue(() => sum(lit(1))),
      from(Lips)
        .groupBy((l) => pgGroupingSets([l.material], []))
        .selectValue(() => sum(lit(1))),
    ];
    for (const q of cases) assert.throws(() => q.toSql(other), /HanaDialect|MysqlDialect|PostgreSQL|PostgresDialect/);
    assert.throws(() => postgres.render(cases[0]!), /DAYS_BETWEEN is a HanaDialect function; this database uses PostgreSQL/);
    assert.throws(() => mysql.render(cases[7]!), /CUBE is a HanaDialect function/);
    assert.throws(() => mysql.render(cases[10]!), /GROUPING SETS is a PostgresDialect function/);
  });

  it("HANA 的非 ASCII 字面量带 N 前缀，类型转换用 NVARCHAR", () => {
    const s = hana.render(from(Lips).selectValue((l) => cast(l.unit, CastType.text(10)))).sql;
    assert.ok(s.startsWith("SELECT CAST(LIPS.VRKME AS NVARCHAR(10))"), s);
    assert.ok(hana.render(from(Lips).selectValue(() => lit("件"))).sql.startsWith("SELECT N'件'"));
  });
});
