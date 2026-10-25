import assert from "node:assert/strict";
import { after, before, describe } from "node:test";
import { filter, type Database, type DatabaseOptions, type Driver, type Table } from "@zerodep-dev/query-core";
import { ClientEntity } from "../../fixtures/erp.js";
import { createSampleData } from "./sample-data.js";
import { TARGETS, type DbName, type LiveConfig, type Target } from "./targets.js";

/**
 * 一个库的实库测试上下文，由 {@link liveSuite} 交给套件：套件开始前连接、建 QUERY_IT schema 和共享的样例数据
 * （sample-data.ts），结束后断开。成员只能在测试和钩子里用，套件函数本身执行时还没有连接。
 */
export class Live {
  #driver: Driver | undefined;
  #db: Database | undefined;
  #plain: Database | undefined;
  #close: (() => Promise<void>) | undefined;

  constructor(
    readonly target: Target,
    readonly config: LiveConfig,
  ) {}

  /** 共享的实例：带全局过滤器，只看客户端 100 的行（样例数据里客户端 200 的行被挡掉）。 */
  get db(): Database {
    return this.#db ?? assert.fail(`live ${this.target.title} database not connected`);
  }

  /** 连接池上的原始驱动，执行手写的 SQL。 */
  get driver(): Driver {
    return this.#driver ?? assert.fail(`live ${this.target.title} database not connected`);
  }

  /** 这个库是不是 names 之一；各库结果不同的地方写成 `if (live.is("sqlite")) { ... }`。 */
  is(...names: DbName[]): boolean {
    return names.includes(this.target.name);
  }

  /** 同一个连接池上另建的实例，不带共享实例的过滤器，例如带缓存、监听器或分表配置。 */
  database(options?: DatabaseOptions): Database {
    return this.target.database(this.driver, options);
  }

  /** 手写 SQL 里的标识符，按本库的方言加引号。 */
  quote(identifier: string): string {
    return this.db.dialect.quote(identifier);
  }

  /** 手写 SQL 里的表名 `QUERY_IT.name`，按本库的方言加引号。 */
  table(name: string): string {
    return `${this.quote("QUERY_IT")}.${this.quote(name)}`;
  }

  /** 一条要运行好几秒才返回的手写语句；驱动不能中途取消语句时为 undefined。 */
  get slowStatement(): string | undefined {
    return this.target.slowStatement;
  }

  /** 依次执行手写的语句；"-" 开头的允许失败，例如删掉可能不存在的表。 */
  async execute(...statements: string[]): Promise<void> {
    for (const statement of statements) {
      const optional = statement.startsWith("-");
      try {
        await this.driver.query(optional ? statement.slice(1) : statement, []);
      } catch (e) {
        if (!optional) throw new Error(`${e instanceof Error ? e.message : String(e)}\n${statement}`, { cause: e });
      }
    }
  }

  /** 删掉这些表再按声明重建（`db.schema().plan(...)`）；有外键时先列被引用的表，删除按相反的顺序。 */
  async recreate(...tables: Table<unknown>[]): Promise<void> {
    const plain = this.#plain ?? assert.fail("not connected");
    await this.execute(...tables.toReversed().map((t) => `-DROP TABLE ${this.table(plain.schema().declared(t).name)}`));
    await (await plain.schema().plan(...tables)).apply();
  }

  /** 不带任何配置的实例，只给样例数据用。 */
  get plain(): Database {
    return this.#plain ?? assert.fail("not connected");
  }

  /** 连接，建 schema、执行库自己的准备语句，再写样例数据。 */
  async open(): Promise<void> {
    const connection = await this.target.connect(this.config);
    this.#driver = connection.driver;
    this.#close = connection.close;
    await this.execute(...this.target.createSchema, ...this.target.setup);
    this.#plain = this.target.database(connection.driver);
    this.#db = this.target.database(connection.driver, { filters: [filter(ClientEntity, (r) => r.client.eq("100"))] });
    await createSampleData(this);
  }

  /** 断开连接。 */
  async close(): Promise<void> {
    await this.#close?.();
  }
}

/** 测试套件适用的库：only 为空表示全部，再去掉 except。 */
export interface LiveOptions {
  readonly only?: readonly DbName[];
  readonly except?: readonly DbName[];
}

/**
 * 实库测试套件：在每个选中的库上各跑一遍 body，报告里显示成 `[MySQL] 分页`。与 Java 的 `@LiveTest` 规则相同：
 * - options 的 only、except 划定这个套件适用的库；
 * - 环境变量 QUERY_DATABASES 是逗号分隔的库名（hana、postgres、mysql、polardb、sqlite、sqlite-wasm），只跑列出的库；不设时全部都跑；
 * - 选中的库没有连接变量 QUERY_{前缀}_URL / _USER / _PASSWORD 时整个套件记为跳过；设了 QUERY_REQUIRE_LIVE（CI）时改为失败。
 *
 * ```ts
 * liveSuite("分页", (live) => {
 *   it("第二页取第三四张交货单", async () => assert.deepEqual((await live.db.page(...)).rows, ["D3", "D4"]));
 * });
 * liveSuite("MySQL 专属写法", { only: ["mysql", "polardb"] }, (live) => { ... });
 * ```
 *
 * 套件自己的表在 body 里的 `before(() => live.recreate(...))` 建，每个库建一次。
 */
export function liveSuite(title: string, body: (live: Live) => void): void;
export function liveSuite(title: string, options: LiveOptions, body: (live: Live) => void): void;
export function liveSuite(title: string, optionsOrBody: LiveOptions | ((live: Live) => void), maybeBody?: (live: Live) => void): void {
  const options = typeof optionsOrBody === "function" ? {} : optionsOrBody;
  const body = typeof optionsOrBody === "function" ? optionsOrBody : maybeBody!;
  for (const target of selected(options)) {
    const config = configOf(target);
    describe(`[${target.title}] ${title}`, typeof config === "string" ? { skip: config } : {}, () => {
      const live = new Live(target, config as LiveConfig);
      before(() => live.open());
      after(() => live.close());
      body(live);
    });
  }
}

/** 这个套件要在哪些库上运行。 */
function selected(options: LiveOptions): Target[] {
  const names = process.env.QUERY_DATABASES;
  const chosen = names === undefined || names.trim() === "" ? undefined : names.split(",").map((n) => named(n.trim())).filter((n) => n !== undefined);
  return TARGETS.filter(
    (t) => (options.only === undefined || options.only.includes(t.name)) && !(options.except ?? []).includes(t.name) && (chosen === undefined || chosen.includes(t.name)),
  );
}

/** QUERY_DATABASES 里的库名；pg 是 postgres 的别名，空串跳过，不认识时报错并列出认识的名字。 */
function named(name: string): DbName | undefined {
  if (name === "") return undefined;
  const key = name.toLowerCase() === "pg" ? "postgres" : name.toLowerCase();
  const target = TARGETS.find((t) => t.name === key);
  if (target === undefined) throw new Error(`QUERY_DATABASES names an unknown database '${name}'; known: ${TARGETS.map((t) => t.name).join(", ")}`);
  return target.name;
}

/** 连接设置；本地库给占位值，没有连接变量时返回跳过的原因。 */
function configOf(target: Target): LiveConfig | string {
  if (target.variables === undefined) return { host: "", port: 0, database: undefined, user: "", password: "" };
  const prefix = `QUERY_${target.variables}`;
  const url = process.env[`${prefix}_URL`];
  const user = process.env[`${prefix}_USER`];
  const password = process.env[`${prefix}_PASSWORD`];
  const missing = `${prefix}_URL/_USER/_PASSWORD not set`;
  if (url === undefined || user === undefined || password === undefined) {
    if (process.env.QUERY_REQUIRE_LIVE !== undefined) throw new Error(`${missing} but QUERY_REQUIRE_LIVE is`);
    return `${missing}; skipping live ${target.title} tests`;
  }
  const parsed = new URL(url.replace(/^jdbc:/, ""));
  const database = parsed.pathname.replace(/^\//, "");
  return { host: parsed.hostname, port: Number(parsed.port), database: database === "" ? undefined : database, user, password };
}
