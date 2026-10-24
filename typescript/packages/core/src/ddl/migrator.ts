import type { Dialect, DialectClass } from "../sql/dialect.js";
import type { Rendered } from "../sql/writer.js";
import type { Write } from "../write/write.js";
import { columnType, type ColumnDef, type ColumnType, type ForeignKeyDef, type IndexDef, type TableDef, type TableOption } from "./model.js";
import type { SchemaSyntax } from "./syntax.js";

/** Web Crypto（Node 19 起和浏览器都有），只取用到的部分；核心包不依赖 DOM 或 Node 的类型声明。 */
interface WebCrypto {
  readonly subtle: { digest(algorithm: string, data: Uint8Array): Promise<ArrayBuffer> };
  randomUUID(): string;
}

/** 文本编码器（Node 和浏览器都有）；核心包不依赖 DOM 的类型声明。 */
const utf8 = new (globalThis as unknown as { TextEncoder: new () => { encode(text: string): Uint8Array } }).TextEncoder();

/** 取 Web Crypto；浏览器里只在安全上下文（https、localhost）中有。 */
function webCrypto(): WebCrypto {
  const crypto = (globalThis as { crypto?: WebCrypto }).crypto;
  if (crypto?.subtle === undefined) throw new Error("Migrations need Web Crypto (Node 19 or later, or a secure browser context)");
  return crypto;
}

/** 等待 ms 毫秒。 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => (globalThis as unknown as { setTimeout(fn: () => void, ms: number): unknown }).setTimeout(resolve, ms));
}

/** 历史表名。 */
export const MIGRATION_HISTORY = "QUERY_MIGRATION";

/** 锁表名。 */
export const MIGRATION_LOCK = "QUERY_MIGRATION_LOCK";

/**
 * 在迁移里手写表结构，不依赖实体类（实体类以后会改，旧迁移的含义不能跟着变）：
 * `tableBuilder("APP", "S_ITEM").column("ID", columnType("BIGINT")).identity().column("CODE", columnType("TEXT", 20)).notNull().primaryKey("ID").build()`。
 * `notNull`、`defaultValue`、`identity`、`comment` 作用于最近加的一列。与 Java 的 TableBuilder 一致。
 */
export class TableBuilder {
  readonly #columns: ColumnDef[] = [];
  readonly #primaryKey: string[] = [];
  readonly #indexes: IndexDef[] = [];
  readonly #foreignKeys: ForeignKeyDef[] = [];
  #comment = "";
  readonly #options: TableOption[] = [];

  constructor(
    readonly schema: string,
    readonly name: string,
  ) {}

  /** 加一列，默认可以为 NULL。 */
  column(name: string, type: ColumnType): this {
    this.#columns.push({ name, type, nullable: true, defaultValue: "", identity: false, comment: "", previousName: "" });
    return this;
  }

  /** 最近加的一列不允许 NULL。 */
  notNull(): this {
    return this.#last({ nullable: false });
  }

  /** 最近加的一列的默认值（SQL 文本）。 */
  defaultValue(sql: string): this {
    return this.#last({ defaultValue: sql });
  }

  /** 最近加的一列由数据库生成，同时不允许 NULL。 */
  identity(): this {
    return this.#last({ identity: true, nullable: false });
  }

  /** 最近加的一列的注释。 */
  comment(text: string): this {
    return this.#last({ comment: text });
  }

  /** 表的注释。 */
  tableComment(text: string): this {
    this.#comment = text;
    return this;
  }

  /** 主键列；这些列同时改成不允许 NULL。 */
  primaryKey(...names: string[]): this {
    this.#primaryKey.push(...names);
    for (let i = 0; i < this.#columns.length; i++) if (names.includes(this.#columns[i]!.name)) this.#columns[i] = { ...this.#columns[i]!, nullable: false };
    return this;
  }

  /** 索引。 */
  index(name: string, unique: boolean, ...columns: string[]): this {
    this.#indexes.push({ name, columns, unique });
    return this;
  }

  /** 外键：columns 引用 referencedSchema.referencedTable 的 referencedColumns。 */
  foreignKey(name: string, columns: readonly string[], referencedSchema: string, referencedTable: string, referencedColumns: readonly string[]): this {
    this.#foreignKeys.push({ name, columns, referencedSchema, referencedTable, referencedColumns });
    return this;
  }

  /** 只在 dialect 上加在建表语句末尾的选项，例如分区：`option(PostgresDialect, 'PARTITION BY RANGE ("DAY")')`。 */
  option(dialect: DialectClass, sql: string): this {
    this.#options.push({ dialect, sql });
    return this;
  }

  /** 完成。 */
  build(): TableDef {
    return { schema: this.schema, name: this.name, columns: [...this.#columns], primaryKey: [...this.#primaryKey], indexes: [...this.#indexes], foreignKeys: [...this.#foreignKeys], comment: this.#comment, external: false, options: [...this.#options] };
  }

  /** 改最近加的一列。 */
  #last(change: Partial<ColumnDef>): this {
    if (this.#columns.length === 0) throw new Error("Add a column first");
    this.#columns[this.#columns.length - 1] = { ...this.#columns.at(-1)!, ...change };
    return this;
  }
}

/** 开始描述 schema 里的表 name；schema 为空串表示连接当前的 schema。 */
export function tableBuilder(schema: string, name: string): TableBuilder {
  return new TableBuilder(schema, name);
}

/** 只有名字的表，用于给已有的表加列、建索引等。 */
export function namedTable(schema: string, name: string): TableDef {
  return tableBuilder(schema, name).build();
}

/** 一步：要执行的 DDL，或一条写语句。 */
type Step = { readonly sql: string } | { readonly write: Write; readonly renderings: readonly Rendered[] };

/** 一个迁移登记的步骤，按登记的顺序执行；校验和按渲染出的语句计算。与 Java 的 MigrationSteps 一致。 */
export class MigrationSteps {
  readonly #steps: Step[] = [];

  constructor(
    readonly dialect: Dialect,
    readonly syntax: SchemaSyntax,
    readonly render: (write: Write) => readonly Rendered[],
  ) {}

  /** 建表：CREATE TABLE、注释、索引，外键紧跟其后（被引用的表要先建）。 */
  createTable(table: TableDef): this {
    for (const s of this.syntax.createTable(table)) this.sql(s);
    for (const f of table.foreignKeys) this.sql(this.syntax.addForeignKey(table, f));
    return this;
  }

  /** 给 table 加一列；table 只用名字，见 {@link namedTable}。 */
  addColumn(table: TableDef, column: ColumnDef): this {
    return this.sql(this.syntax.addColumn(table, column));
  }

  /** 把列改成 column 的类型。 */
  alterType(table: TableDef, column: ColumnDef): this {
    return this.sql(this.syntax.alterType(table, column));
  }

  /** 列改名。 */
  renameColumn(table: TableDef, from: string, to: string): this {
    return this.sql(this.syntax.renameColumn(table, from, to));
  }

  /** 建索引。 */
  createIndex(table: TableDef, index: IndexDef): this {
    return this.sql(this.syntax.createIndex(table, index));
  }

  /** 加外键。 */
  addForeignKey(table: TableDef, foreignKey: ForeignKeyDef): this {
    return this.sql(this.syntax.addForeignKey(table, foreignKey));
  }

  /** 一条语句；给了 only 时只在这个方言（及其子类）上执行，其他方言跳过。一条一次执行，不要用分号拼多条。 */
  sql(statement: string, only?: DialectClass): this {
    if (only === undefined || this.dialect instanceof (only as unknown as abstract new () => Dialect)) this.#steps.push({ sql: statement });
    return this;
  }

  /** 数据变更：插入种子数据、回填新列等；用插入或更新写成可以重复执行的。 */
  execute(write: Write): this {
    this.#steps.push({ write, renderings: this.render(write) });
    return this;
  }

  /** 登记的步骤。 */
  get steps(): readonly Step[] {
    return [...this.#steps];
  }

  /** 预览用的语句文本：DDL 原样，写语句是渲染出的 SQL 加参数。 */
  get statements(): string[] {
    return this.#steps.flatMap((s) => ("sql" in s ? [s.sql] : s.renderings.map((r) => `${r.sql}\n-- parameters: ${r.parameters.map(String).join(", ")}`)));
  }
}

/** 一个版本化的迁移；与 Java 的 Migration 一致。 */
export interface Migration {
  /** 版本号，在迁移列表里唯一；按列表的顺序执行，不按字符串排序。 */
  readonly version: string;
  readonly description: string;
  /** 登记这一版要执行的步骤；不要在这里直接访问数据库。 */
  up(steps: MigrationSteps): void;
}

/** 由版本号、说明和登记步骤的函数组成的迁移。 */
export function migration(version: string, description: string, up: (steps: MigrationSteps) => void): Migration {
  return Object.freeze({ version, description, up });
}

/** 历史表里的一行。 */
export interface Applied {
  readonly version: string;
  readonly description: string;
  readonly checksum: string;
  readonly status: string;
  readonly startedAt: string | null;
  readonly finishedAt: string | null;
}

/** 数据库实例提供给迁移器的能力。 */
export interface MigrationBackend {
  query(sql: string, ...parameters: unknown[]): Promise<Record<string, unknown>[]>;
  update(sql: string, ...parameters: unknown[]): Promise<number>;
  run(statements: readonly string[]): Promise<void>;
  render(write: Write): readonly Rendered[];
  execute(write: Write): Promise<unknown>;
  /** 在一个事务里执行 work，work 收到经由事务执行的能力。 */
  transaction<R>(work: (backend: MigrationBackend) => Promise<R>): Promise<R>;
}

/** 此刻的时间戳文本 'YYYY-MM-DD HH:MM:SS.mmm'（本地时间）。 */
function now(): string {
  const d = new Date();
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

/**
 * 版本化迁移的执行器，由 `db.migrator(migrations)` 得到；与 Java 的 Migrator 一致：历史表 QUERY_MIGRATION 记版本、说明、校验和、状态和时间，
 * 锁表 QUERY_MIGRATION_LOCK 的一行让多个实例互斥；PostgreSQL 每个迁移一个事务，MySQL、HANA 失败时记为 failed，修好后 {@link repair}。
 */
export class Migrator {
  readonly #owner = webCrypto().randomUUID();

  constructor(
    readonly migrations: readonly Migration[],
    readonly dialect: Dialect,
    readonly syntax: SchemaSyntax,
    readonly backend: MigrationBackend,
    readonly schema = "",
    readonly lockTimeoutMs = 60_000,
  ) {
    const seen = new Set<string>();
    for (const m of migrations) {
      if (seen.has(m.version)) throw new Error(`Migration version ${m.version} appears twice`);
      seen.add(m.version);
    }
  }

  /** 历史表和锁表放在 schema 里；默认是连接当前的 schema。 */
  historySchema(schema: string): Migrator {
    return new Migrator(this.migrations, this.dialect, this.syntax, this.backend, schema, this.lockTimeoutMs);
  }

  /** 等待别的实例释放迁移锁的最长毫秒数，默认一分钟。 */
  lockTimeout(ms: number): Migrator {
    return new Migrator(this.migrations, this.dialect, this.syntax, this.backend, this.schema, ms);
  }

  /** 历史表里的各行，按开始时间；历史表还不存在时为空。 */
  async history(): Promise<Applied[]> {
    if ((await this.syntax.read(this.backend.query, this.schema, MIGRATION_HISTORY)) === undefined) return [];
    const rows = await this.backend.query(this.#cols("SELECT {VERSION}, {DESCRIPTION}, {CHECKSUM}, {STATUS}, {STARTED_AT}, {FINISHED_AT} FROM ") + this.#table(MIGRATION_HISTORY) + this.#cols(" ORDER BY {STARTED_AT}"));
    return rows.map((r) => ({
      version: String(r.version),
      description: r.description == null ? "" : String(r.description),
      checksum: r.checksum == null ? "" : String(r.checksum),
      status: String(r.status),
      startedAt: r.started_at == null ? null : String(r.started_at),
      finishedAt: r.finished_at == null ? null : String(r.finished_at),
    }));
  }

  /** 还没执行的迁移，按列表顺序。 */
  async pending(): Promise<Migration[]> {
    const applied = byVersion(await this.history());
    return this.migrations.filter((m) => !applied.has(m.version));
  }

  /** 还没执行的迁移各自会执行的语句，按版本；不执行任何东西。 */
  async plan(): Promise<Map<string, string[]>> {
    return new Map((await this.pending()).map((m) => [m.version, this.#record(m).statements]));
  }

  /** 检查历史与迁移列表是否一致；不一致时抛错，列出全部问题。 */
  async verify(): Promise<void> {
    const problems = await this.#problems(byVersion(await this.history()));
    if (problems.length > 0) throw new Error(`Migration history does not match the migrations:\n${problems.join("\n")}`);
  }

  /** 执行全部还没执行的迁移，返回执行了的版本。 */
  migrateToLatest(): Promise<string[]> {
    return this.migrateTo(undefined);
  }

  /** 执行到 version（含）为止还没执行的迁移，返回执行了的版本。 */
  async migrateTo(version: string | undefined): Promise<string[]> {
    if (version !== undefined && !this.migrations.some((m) => m.version === version)) throw new Error(`No migration ${version}`);
    await this.#ensureTables();
    await this.#lock();
    try {
      const applied = byVersion(await this.history());
      const problems = await this.#problems(applied);
      if (problems.length > 0) throw new Error(`Migration history does not match the migrations:\n${problems.join("\n")}`);
      const done: string[] = [];
      for (const m of this.migrations) {
        if (!applied.has(m.version)) {
          await this.#run(m);
          done.push(m.version);
        }
        if (m.version === version) break;
      }
      return done;
    } finally {
      await this.#unlock();
    }
  }

  /** 接管已有的库：把 version（含）之前的迁移记为已执行（状态 baseline），不执行它们。 */
  async baseline(version: string): Promise<void> {
    if (!this.migrations.some((m) => m.version === version)) throw new Error(`No migration ${version}`);
    await this.#ensureTables();
    await this.#lock();
    try {
      const applied = byVersion(await this.history());
      for (const m of this.migrations) {
        if (!applied.has(m.version)) {
          const t = now();
          await this.backend.update(
            "INSERT INTO " + this.#table(MIGRATION_HISTORY) + this.#cols(" ({VERSION}, {DESCRIPTION}, {CHECKSUM}, {STATUS}, {STARTED_AT}, {FINISHED_AT}) VALUES (?, ?, ?, ?, ?, ?)"),
            m.version,
            m.description,
            await checksum(this.#record(m)),
            "baseline",
            t,
            t,
          );
        }
        if (m.version === version) break;
      }
    } finally {
      await this.#unlock();
    }
  }

  /** 删掉 version 失败的那一行，修好数据库后再执行 {@link migrateToLatest} 会重新执行它。 */
  async repair(version: string): Promise<void> {
    const rows = await this.backend.update("DELETE FROM " + this.#table(MIGRATION_HISTORY) + this.#cols(" WHERE {VERSION} = ? AND {STATUS} = ?"), version, "failed");
    if (rows === 0) throw new Error(`Migration ${version} has not failed`);
  }

  /** 执行一个迁移：先记 started，执行步骤，成功记 success，失败记 failed。PostgreSQL 的步骤和 success 在一个事务里。 */
  async #run(m: Migration): Promise<void> {
    const steps = this.#record(m);
    await this.backend.update(
      "INSERT INTO " + this.#table(MIGRATION_HISTORY) + this.#cols(" ({VERSION}, {DESCRIPTION}, {CHECKSUM}, {STATUS}, {STARTED_AT}) VALUES (?, ?, ?, ?, ?)"),
      m.version,
      m.description,
      await checksum(steps),
      "started",
      now(),
    );
    const work = async (backend: MigrationBackend) => {
      for (const s of steps.steps) {
        if ("sql" in s) await backend.run([s.sql]);
        else await backend.execute(s.write);
      }
      await this.#mark(backend, m.version, "success");
    };
    try {
      if (this.syntax.transactionalDdl) await this.backend.transaction(work);
      else await work(this.backend);
    } catch (e) {
      await this.#mark(this.backend, m.version, "failed");
      throw new Error(`Migration ${m.version} (${m.description}) failed: ${e instanceof Error ? e.message : String(e)}`, { cause: e });
    }
  }

  /** 经由 backend 改历史表里一个版本的状态和结束时间。 */
  async #mark(backend: MigrationBackend, version: string, status: string): Promise<void> {
    await backend.update("UPDATE " + this.#table(MIGRATION_HISTORY) + this.#cols(" SET {STATUS} = ?, {FINISHED_AT} = ? WHERE {VERSION} = ?"), status, now(), version);
  }

  /** 让迁移登记它的步骤。 */
  #record(m: Migration): MigrationSteps {
    const steps = new MigrationSteps(this.dialect, this.syntax, this.backend.render);
    m.up(steps);
    return steps;
  }

  /** 历史与列表的不一致：失败或未完成的版本、未知的版本、校验和不同、中间有空缺。 */
  async #problems(applied: Map<string, Applied>): Promise<string[]> {
    const out: string[] = [];
    const known = new Set<string>();
    let gap = false;
    for (const m of this.migrations) {
      known.add(m.version);
      const a = applied.get(m.version);
      if (a === undefined) {
        gap = true;
        continue;
      }
      if (gap) out.push(`${m.version} was applied although an earlier migration was not`);
      if (a.status === "failed" || a.status === "started") out.push(`${m.version} is ${a.status}; fix the database by hand, then call repair("${m.version}")`);
      else if (a.checksum !== (await checksum(this.#record(m)))) out.push(`${m.version} was changed after it was applied (checksum differs); write a new migration instead`);
    }
    for (const v of applied.keys()) if (!known.has(v)) out.push(`${v} is in the history but not among the migrations`);
    return out;
  }

  /** 历史表和锁表不存在时建上，锁表放入唯一的一行。 */
  async #ensureTables(): Promise<void> {
    const q = this.backend.query;
    if ((await this.syntax.read(q, this.schema, MIGRATION_HISTORY)) === undefined) {
      await this.backend.run(
        this.syntax.createTable(
          tableBuilder(this.schema, MIGRATION_HISTORY)
            .column("VERSION", columnType("TEXT", 100))
            .column("DESCRIPTION", columnType("TEXT", 200))
            .column("CHECKSUM", columnType("TEXT", 64))
            .column("STATUS", columnType("TEXT", 10))
            .notNull()
            .column("STARTED_AT", columnType("TIMESTAMP"))
            .column("FINISHED_AT", columnType("TIMESTAMP"))
            .primaryKey("VERSION")
            .build(),
        ),
      );
    }
    if ((await this.syntax.read(q, this.schema, MIGRATION_LOCK)) === undefined) {
      await this.backend.run(
        this.syntax.createTable(tableBuilder(this.schema, MIGRATION_LOCK).column("ID", columnType("INTEGER")).column("OWNER", columnType("TEXT", 64)).column("LOCKED_AT", columnType("TIMESTAMP")).primaryKey("ID").build()),
      );
    }
    if ((await q(this.#cols("SELECT {ID} FROM ") + this.#table(MIGRATION_LOCK) + this.#cols(" WHERE {ID} = ?"), 1)).length === 0) {
      // 另一个实例同时插入了这一行时插入失败，忽略即可。
      await this.backend.update("INSERT INTO " + this.#table(MIGRATION_LOCK) + this.#cols(" ({ID}) VALUES (?)"), 1).catch(() => undefined);
    }
  }

  /** 占用锁行；别的实例占着时每半秒重试一次，超过 lockTimeoutMs 报错。 */
  async #lock(): Promise<void> {
    const deadline = Date.now() + this.lockTimeoutMs;
    while ((await this.backend.update("UPDATE " + this.#table(MIGRATION_LOCK) + this.#cols(" SET {OWNER} = ?, {LOCKED_AT} = ? WHERE {ID} = ? AND {OWNER} IS NULL"), this.#owner, now(), 1)) === 0) {
      if (Date.now() > deadline) throw new Error(`Another process holds the migration lock in ${this.#table(MIGRATION_LOCK)}; if it crashed, clear OWNER by hand`);
      await sleep(500);
    }
  }

  /** 释放锁行。 */
  async #unlock(): Promise<void> {
    await this.backend.update("UPDATE " + this.#table(MIGRATION_LOCK) + this.#cols(" SET {OWNER} = NULL, {LOCKED_AT} = NULL WHERE {ID} = ? AND {OWNER} = ?"), 1, this.#owner);
  }

  /** 把 SQL 片段里的 {列名} 换成按方言加引号的列名，问号换成方言的占位符由调用方的 backend 负责。 */
  #cols(sql: string): string {
    return sql.replace(/\{([A-Z_]+)\}/g, (_, name: string) => this.dialect.quote(name));
  }

  /** 写出 `schema.表名`。 */
  #table(name: string): string {
    return this.schema === "" ? this.dialect.quote(name) : `${this.dialect.quote(this.schema)}.${this.dialect.quote(name)}`;
  }
}

/** 步骤语句（UTF-8）的 SHA-256，十六进制小写。 */
async function checksum(steps: MigrationSteps): Promise<string> {
  const digest = await webCrypto().subtle.digest("SHA-256", utf8.encode(steps.statements.join("\n")));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** 按版本号索引历史。 */
function byVersion(history: readonly Applied[]): Map<string, Applied> {
  return new Map(history.map((a) => [a.version, a]));
}
