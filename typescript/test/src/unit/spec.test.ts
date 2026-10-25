import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { filter, Write, type Database, type DatabaseOptions, type Driver } from "@zerodep-dev/query-core";
import { HanaDatabase } from "@zerodep-dev/query-hana";
import { MysqlDatabase } from "@zerodep-dev/query-mysql";
import { PolardbDatabase } from "@zerodep-dev/query-polardb";
import { PostgresDatabase } from "@zerodep-dev/query-postgres";
import { SqliteDatabase } from "@zerodep-dev/query-sqlite";
import { ClientEntity } from "../fixtures/erp.js";
import { dialectCases, polardbCases, sharedCases, unsupportedCases } from "../fixtures/spec-cases.js";
import { SHARDS } from "../fixtures/shards.js";

/**
 * Golden SQL shared with the Java tests: spec/render/<case>.<dialect>.sql holds the parameters on
 * its first line and the statement after it, with `?` placeholders; PostgreSQL's numbered
 * placeholders are compared as `?`.
 */
const SPEC = fileURLToPath(new URL("../../../../spec/render/", import.meta.url));

const never: Driver = {
  query: () => Promise.reject(new Error("spec rendering never connects")),
};

/** 金样例的方言：各自的数据库与方言专属用例。 */
type SpecDialect = keyof typeof dialectCases | "polardb";

const databases: Record<SpecDialect, (options: DatabaseOptions) => Database> = {
  hana: (options) => new HanaDatabase(never, options),
  postgres: (options) => new PostgresDatabase(never, options),
  mysql: (options) => new MysqlDatabase(never, options),
  polardb: (options) => new PolardbDatabase(never, options),
  sqlite: (options) => new SqliteDatabase(never, options),
};

const specificCases: Record<SpecDialect, Readonly<Record<string, { readonly filtered: boolean; readonly query: () => unknown }>>> = { ...dialectCases, polardb: polardbCases };

/** 写法沿用别的方言：没有本方言金样例文件的用例和那个方言的文件比对；与 Java RenderSpec 的 fallbackDialect 一致。 */
const fallbacks: Partial<Record<SpecDialect, SpecDialect>> = { polardb: "mysql" };

function golden(file: string): { parameters: unknown; sql: string } {
  const content = readFileSync(SPEC + file, "utf8").replace(/\r\n/g, "\n");
  const newline = content.indexOf("\n");
  const header = content.slice(0, newline);
  assert.match(header, /^-- parameters: /, `${file} starts with its parameters`);
  return { parameters: JSON.parse(header.slice("-- parameters: ".length)), sql: content.slice(newline + 1).replace(/\n$/, "") };
}

describe("渲染金样例", () => {
  for (const dialect of Object.keys(databases) as SpecDialect[]) {
    const unsupported = dialect === "polardb" ? [] : (unsupportedCases[dialect] ?? []);
    const cases = { ...Object.fromEntries(Object.entries(sharedCases).filter(([name]) => !unsupported.includes(name))), ...specificCases[dialect] } as typeof sharedCases;
    const fallback = fallbacks[dialect];
    for (const name of unsupported) {
      it(`${dialect} 不支持 ${name}`, () => {
        const statement = sharedCases[name]!.query();
        const db = databases[dialect]({ shards: SHARDS });
        assert.throws(() => (statement instanceof Write ? db.render(statement) : db.render(statement)));
        assert.ok(!existsSync(SPEC + `${name}.${dialect}.sql`), `${name} has a golden file but is unsupported`);
      });
    }
    const filtered = databases[dialect]({ filters: [filter(ClientEntity, (r) => r.client.eq("100"))], shards: SHARDS });
    const unfiltered = databases[dialect]({ shards: SHARDS });

    for (const [name, spec] of Object.entries(cases)) {
      it(`${dialect} 渲染 ${name} 与金样例一致`, () => {
        const own = `${name}.${dialect}.sql`;
        const file = existsSync(SPEC + own) || fallback === undefined ? own : `${name}.${fallback}.sql`;
        assert.ok(existsSync(SPEC + file), `spec/render/${own} is missing; the Java spec tests write it with QUERY_SPEC_WRITE=1`);
        const expected = golden(file);
        const db = spec.filtered ? filtered : unfiltered;
        const statement = spec.query();
        const all = statement instanceof Write ? db.render(statement) : [db.render(statement)];
        assert.equal(all.length, 1, "a write case renders one statement");
        const rendered = all[0]!;
        assert.equal(rendered.sql.replace(/\$\d+/g, "?"), expected.sql);
        assert.deepEqual(
          rendered.parameters.map((p) => String(p)),
          expected.parameters,
        );
      });
    }

    it(`${dialect} 的每个金样例在 TS 中都有对应的查询`, () => {
      const files = readdirSync(SPEC).filter((f) => f.endsWith(`.${dialect}.sql`));
      const missing = files.map((f) => f.slice(0, -`.${dialect}.sql`.length)).filter((name) => !(name in cases));
      assert.deepEqual(missing, []);
    });
  }
});
