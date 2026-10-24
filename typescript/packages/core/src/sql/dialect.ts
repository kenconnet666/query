import type { CastType, SqlType } from "../schema/types.js";
import type { Renderable, SqlWriter } from "./writer.js";

/** 写出一次函数调用。 */
export type FunctionRenderer = (w: SqlWriter, args: readonly Renderable[]) => void;

/** 方言类。`FunctionDef.only` 和子句用它指明所属方言；它的构造函数可以是 protected。 */
export type DialectClass = Function & { readonly prototype: Dialect };

/**
 * 函数的身份。渲染按以下顺序查找：数据库实例的改写、方言的注册、本定义的标准渲染。
 * 归某个方言所有的定义在其他方言上拒绝渲染，不去猜一个等价写法。
 */
export class FunctionDef {
  readonly #standard: FunctionRenderer;
  readonly #owner: DialectClass | undefined;

  private constructor(
    readonly name: string,
    standard: FunctionRenderer | undefined,
    owner: DialectClass | undefined,
  ) {
    this.#standard = standard ?? ((w, args) => FunctionDef.call(w, name, args));
    this.#owner = owner;
  }

  /** 在所有数据库上都写成 NAME(arg, ...) 的函数，除非另给标准渲染。 */
  static of(name: string, standard?: FunctionRenderer): FunctionDef {
    return new FunctionDef(name, standard, undefined);
  }

  /** 只存在于某一个方言（及其子类）的函数。 */
  static only(owner: DialectClass, name: string, standard?: FunctionRenderer): FunctionDef {
    return new FunctionDef(name, standard, owner);
  }

  /** 按实例改写、方言注册、标准渲染的顺序选出渲染器并写出调用。 */
  render(w: SqlWriter, args: readonly Renderable[]): void {
    const dialect: Dialect = w.dialect;
    let renderer = w.functionOverride(this) ?? dialect.renderer(this);
    if (renderer === undefined) {
      if (this.#owner !== undefined && !(dialect instanceof this.#owner)) {
        throw new Error(
          `${this.name} is a ${this.#owner.name} function; this database uses ${w.dialect.name}. ` +
            "Use that dialect's equivalent, or register a renderer for it.",
        );
      }
      renderer = this.#standard;
    }
    renderer(w, args);
  }

  /** 写出 NAME(arg1, arg2, ...)。 */
  static call(w: SqlWriter, name: string, args: readonly Renderable[]): void {
    w.sql(name).sql("(").join(args, ", ").sql(")");
  }

  /** 函数名。 */
  toString(): string {
    return this.name;
  }
}

/**
 * SELECT 语句中仅限某个方言的部分，例如 HANA 的 `WITH HINT(...)`、
 * PostgreSQL 的 `DISTINCT ON (...)` 或 MySQL 的优化器提示。在其他方言上渲染会被拒绝。
 */
export interface Clause extends Renderable {
  /** `hint` 紧跟在 SELECT 之后，`distinct` 取代 DISTINCT，`end` 在 ORDER BY 和 LIMIT 之后。 */
  readonly position: "hint" | "distinct" | "end";
  /** 这个子句所属的方言；在所有方言都合法的子句上缺省。 */
  readonly dialect?: DialectClass;
}

/** 加锁读遇到别的事务锁住的行时：等待、不等待立即报错（NOWAIT）、跳过（SKIP LOCKED，HANA 写作 IGNORE LOCKED）。 */
export type Lock = "wait" | "nowait" | "skipLocked";

/**
 * 联表的 UPDATE 或 DELETE（方言包的 updateJoin、deleteJoin）交给方言写出的内容。三个库的写法不同，一行匹配到多行时的行为也不同
 * （PostgreSQL、MySQL 取其中一行，HANA 的 MERGE 报错），所以只由方言包提供。
 */
export interface JoinWrite {
  /** 写出被改的表和别名。 */
  readonly target: Renderable;
  /** 写出被改表的别名，MySQL 的 `DELETE 别名 FROM ...` 要用。 */
  readonly targetAlias: Renderable;
  /** 写出被改的表，不带别名。 */
  readonly table: Renderable;
  /** 写出连接的表和别名。 */
  readonly source: Renderable;
  /** 连接条件，已带上连接表的全局过滤器和逻辑删除条件。 */
  readonly on: Renderable;
  /** 被改表的过滤条件加上 where 的条件；都没有时为 undefined。 */
  readonly where: Renderable | undefined;
  /** 写出 SET 的列名（不带别名），与 targetColumns、values 一一对应；DELETE 时为空。 */
  readonly columns: readonly Renderable[];
  /** 写出带别名的被改列，MySQL 写在 SET 左边。 */
  readonly targetColumns: readonly Renderable[];
  /** 新值，可以引用两张表的列。 */
  readonly values: readonly Renderable[];
  /** 写出被改表的主键列名（不带别名），HANA 的 MERGE 按它对上行。 */
  readonly keys: readonly Renderable[];
  /** 写出带别名的主键列，HANA 写在 MERGE 的 USING 查询里。 */
  readonly keyColumns: readonly Renderable[];
}

/** 写出 `on` 再接上 `AND where`（有 where 时），PostgreSQL 的 WHERE 和 HANA 的 EXISTS 子查询用。 */
export function renderOnAndWhere(w: SqlWriter, j: JoinWrite): void {
  w.render(j.on);
  if (j.where !== undefined) w.sql(" AND ").render(j.where);
}

/**
 * 插入或更新一行（`insertInto(...).values(row).onConflict(...)`）交给方言写出的内容；各方言的写法差别很大：
 * HANA 用 MERGE，PostgreSQL 用 ON CONFLICT，MySQL 用 ON DUPLICATE KEY UPDATE。列名都未加引号。
 */
export interface Upsert {
  /** 写出 `schema.表名`。 */
  readonly table: Renderable;
  /** 插入的列名，与 values 一一对应。 */
  readonly columns: readonly string[];
  readonly values: readonly Renderable[];
  /** 判断冲突的列名，都在 columns 里；MySQL 不用它，由表的主键和唯一索引决定。 */
  readonly keys: readonly string[];
  /** 冲突时更新为插入值的列名；与 assignments 都为空时冲突了什么也不做。 */
  readonly updates: readonly string[];
  /** 冲突时另外更新的列及其值（例如审计填充的修改时间）。 */
  readonly assignments: readonly (readonly [string, Renderable])[];
}

/** 冲突时是否什么也不做。 */
export function doNothing(u: Upsert): boolean {
  return u.updates.length === 0 && u.assignments.length === 0;
}

/** 写出列名清单 `("A", "B")`。 */
export function columnList(w: SqlWriter, columns: readonly string[]): void {
  w.sql("(");
  columns.forEach((c, i) => {
    if (i > 0) w.sql(", ");
    w.identifier(c);
  });
  w.sql(")");
}

/** 方言可以写成字面量的类型；其他类型即使写入器内联取值也仍作为参数。 */
const LITERAL_KINDS = new Set(["text", "integer", "bigint", "double", "decimal", "boolean", "date", "timestamp"]);

/** 字面量可以原样携带的数字文本：`decimal` 接受的形式，都是合法的 SQL 数字字面量。 */
const NUMBER_TEXT = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** 标准 SQL、PostgreSQL 和 HANA 的保留字；见 `Dialect.isReserved`。 */
const RESERVED = new Set(
  `ALL ALTER ANALYSE ANALYZE AND ANY ARRAY AS ASC ASYMMETRIC AUTHORIZATION BEFORE BEGIN BETWEEN BINARY BOTH BY CASE CAST CHAR CHECK COLLATE COLLATION COLUMN CONCURRENTLY CONDITION CONNECT CONSTRAINT CREATE CROSS CUBE CURRENT_CATALOG CURRENT_DATE CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DECLARE DEFAULT DEFERRABLE DELETE DESC DISTINCT DO DROP ELSE ELSEIF END EXCEPT EXCEPTION EXEC EXISTS FALSE FETCH FOR FOREIGN FREEZE FROM FULL GRANT GROUP HAVING IF ILIKE IN INITIALLY INNER INOUT INSERT INTERSECT INTO IS ISNULL JOIN LATERAL LEADING LEFT LIKE LIMIT LOCALTIME LOCALTIMESTAMP LOOP MINUS NATURAL NCHAR NOT NOTNULL NULL OFFSET ON ONLY OR ORDER OUT OUTER OVERLAPS PLACING PRIMARY PRIOR REFERENCES RETURN RETURNING RETURNS RIGHT ROLLUP ROWID SELECT SESSION_USER SET SIMILAR SOME SQL START SYMMETRIC SYSTEM_USER TABLE TABLESAMPLE THEN TO TOP TRAILING TRUE UNION UNIQUE UNKNOWN UPDATE USER USING VALUES VARIADIC VERBOSE WHEN WHERE WHILE WINDOW WITH`.split(" "),
);

/**
 * 可开放继承的方言基类。它只负责语法：标识符的引用与大小写折叠、字面量、占位符、
 * 分页和 CAST 的类型名。方言已知的函数渲染在其构造函数里用 {@link register} 注册，
 * 不会按方言名做分支判断。
 */
export abstract class Dialect {
  readonly #functions = new Map<FunctionDef, FunctionRenderer>();

  /** 方言名，出现在错误信息和数据库默认名里。 */
  abstract readonly name: string;

  /** 标识符不需要引号时为真，例如 HANA 的大写名称。 */
  protected abstract isBare(identifier: string): boolean;

  /** 未加引号的标识符折叠成的大小写，使生成的别名可以不加引号。 */
  abstract foldCase(identifier: string): string;

  /**
   * 即使写法合规也必须加引号的词，例如实体 `Order` 的别名 `order`。
   * 取标准 SQL、PostgreSQL 和 HANA 保留字的并集；多引用一个词无害。
   */
  protected isReserved(identifier: string): boolean {
    return RESERVED.has(identifier.toUpperCase());
  }

  /** 按需给标识符加双引号，内部的双引号加倍。 */
  quote(identifier: string): string {
    return this.isBare(identifier) && !this.isReserved(identifier) ? identifier : `"${identifier.replaceAll('"', '""')}"`;
  }

  /** 别名来自 {@link SqlWriter.aliasFor}，已经折叠过大小写。 */
  alias(alias: string): string {
    return this.quote(alias);
  }

  /**
   * NULL 也相等的比较（标准 SQL 的 `IS [NOT] DISTINCT FROM`）。默认写成三个库都支持的
   * `CASE WHEN a = b OR (a IS NULL AND b IS NULL) THEN 1 ELSE 0 END = 1`，negate 时比较 0；
   * PostgreSQL 与 MySQL 换成各自的原生写法。
   */
  notDistinct(w: SqlWriter, left: Renderable, right: Renderable, negate: boolean): void {
    w.sql("CASE WHEN ").render(left).sql(" = ").render(right).sql(" OR (").render(left).sql(" IS NULL AND ").render(right);
    w.sql(" IS NULL) THEN 1 ELSE 0 END = ").sql(negate ? "0" : "1");
  }

  /** 没有表的 SELECT 需要的部分：多数数据库什么都不要，HANA 要 `FROM DUMMY`。 */
  fromNothing(_w: SqlWriter): void {}

  /** 第 n 个参数的占位符，n 从 1 开始计数。 */
  /**
   * 插入一行并取回生成值时追加在语句后面的子句，例如 PostgreSQL 的 `RETURNING "ID"`；不支持时为 undefined，
   * 这时用驱动返回的 insertId（MySQL）或 {@link identityQuery}（HANA）。column 是未加引号的列名。
   */
  returning(_column: string): string | undefined {
    return undefined;
  }

  /** 在同一个连接上查最近一次插入生成的值的语句（HANA 的 CURRENT_IDENTITY_VALUE()）；不需要时为 undefined。 */
  get identityQuery(): string | undefined {
    return undefined;
  }

  placeholder(_index: number): string {
    return "?";
  }

  /** 写出分页子句 LIMIT / OFFSET；没有 limit 时 OFFSET 单独成行。 */
  /** 写出加锁读的子句：`FOR UPDATE`，加上 `NOWAIT` 或 `SKIP LOCKED`。 */
  lock(w: SqlWriter, lock: Lock): void {
    w.sql(lock === "nowait" ? "FOR UPDATE NOWAIT" : lock === "skipLocked" ? "FOR UPDATE SKIP LOCKED" : "FOR UPDATE");
  }

  /** 写出插入或更新一行的语句；各方言的写法不同，没有实现的方言报错。 */
  upsert(_w: SqlWriter, _upsert: Upsert): void {
    throw new Error(`${this.name} has no insert-or-update statement; implement upsert in its dialect`);
  }

  /** 写出联表的 UPDATE；只有方言包实现，见 {@link JoinWrite}。 */
  joinUpdate(_w: SqlWriter, _update: JoinWrite): void {
    throw new Error(`${this.name} has no join update; use a subquery in where(...) instead`);
  }

  /** 写出联表的 DELETE；只有方言包实现，见 {@link JoinWrite}。 */
  joinDelete(_w: SqlWriter, _delete: JoinWrite): void {
    throw new Error(`${this.name} has no join delete; use a subquery in where(...) instead`);
  }

  limit(w: SqlWriter, limit: number | undefined, offset: number | undefined): void {
    if (limit !== undefined) w.sql(`\nLIMIT ${limit}`);
    if (offset !== undefined && offset > 0) w.sql(`${limit === undefined ? "\nOFFSET " : " OFFSET "}${offset}`);
  }

  /** GROUP BY 列表；rollup 为真时，对键的每个前缀输出小计，并输出总计。 */
  groupBy(w: SqlWriter, keys: readonly Renderable[], rollup: boolean): void {
    if (rollup) w.sql("ROLLUP(").join(keys, ", ").sql(")");
    else w.join(keys, ", ");
  }

  /** ORDER BY 的一项；查询没有指定 NULL 位置时 `nullsFirst` 为 undefined。 */
  orderItem(w: SqlWriter, expr: Renderable, descending: boolean, nullsFirst: boolean | undefined): void {
    w.render(expr).sql(descending ? " DESC" : " ASC");
    if (nullsFirst !== undefined) w.sql(nullsFirst ? " NULLS FIRST" : " NULLS LAST");
  }

  /** {@link literal} 能否写出这个值；其他值（如 NaN）即使内联也仍作为参数。 */
  hasLiteral(value: unknown, type: SqlType<unknown>): boolean {
    if (value === null) return true;
    if (!LITERAL_KINDS.has(type.kind)) return false;
    const stored = type.encode(value);
    return typeof stored !== "number" || Number.isFinite(stored);
  }

  /** 写出值的存储形式的字面量；转换器先生效。 */
  literal(w: SqlWriter, value: unknown, type: SqlType<unknown>): void {
    if (value === null) {
      w.sql("NULL");
      return;
    }
    if (!this.hasLiteral(value, type)) throw new TypeError(`No literal form for ${String(value)} (${type.name}); bind it as a parameter`);
    const stored = type.encode(value);
    switch (type.kind) {
      case "text":
        w.sql(this.stringLiteral(String(stored)));
        return;
      case "integer":
      case "bigint":
      case "double":
      case "decimal":
        if (typeof stored !== "number" && typeof stored !== "bigint" && !(typeof stored === "string" && NUMBER_TEXT.test(stored))) {
          throw new TypeError(`${String(stored)} is not a number literal`);
        }
        w.sql(String(stored));
        return;
      case "boolean":
        w.sql(stored ? "TRUE" : "FALSE");
        return;
      case "date":
        w.sql(`DATE '${String(stored)}'`);
        return;
      case "timestamp":
        w.sql(`TIMESTAMP '${String(stored)}'`);
        return;
      default:
        throw new TypeError(`No literal form for a ${type.name} value; bind it as a parameter`);
    }
  }

  /** 字符串字面量，单引号加倍；需要反斜杠转义的方言可以覆盖。 */
  protected stringLiteral(s: string): string {
    return `'${s.replaceAll("'", "''")}'`;
  }

  /** CAST 目标类型的 SQL 类型名。 */
  castType(type: CastType<unknown>): string {
    switch (type.kind) {
      case "text":
        return `VARCHAR(${type.length})`;
      case "decimal":
        return `DECIMAL(${type.length},${type.scale})`;
      case "integer":
        return "INTEGER";
      case "bigint":
        return "BIGINT";
      case "double":
        return "DOUBLE PRECISION";
      case "date":
        return "DATE";
      case "timestamp":
        return "TIMESTAMP";
      case "boolean":
        return "BOOLEAN";
    }
  }

  /** 更长的 IN 列表会拆成多个 IN 谓词，用 OR 连接。 */
  get maxInListSize(): number {
    return 1000;
  }

  /** 一条语句最多能携带的绑定参数个数；HANA、PostgreSQL 和 MySQL 的线路协议都用 16 位计数。 */
  get maxParameters(): number {
    return 65535;
  }

  /** 驱动错误是否表示数据库因运行超时而取消了语句。 */
  isTimeout(_error: unknown): boolean {
    return false;
  }

  /** 驱动放在错误上的 `code`（或其他属性），没有则为 undefined。 */
  protected static errorProperty(error: unknown, name: string): unknown {
    return typeof error === "object" && error !== null ? (error as Record<string, unknown>)[name] : undefined;
  }

  /**
   * 从 `start` 开始的手写 SQL 引号部分在哪里结束：返回其最后一个字符的下标，
   * 那里没有引号部分开始时返回 -1。原生查询只在这些部分之外查找 `:name`。
   * 默认识别 '...' 和 "..."，内部的引号用双写表示。
   */
  quotedEnd(sql: string, start: number): number {
    const c = sql[start];
    return c === "'" || c === '"' ? Dialect.closingQuote(sql, start, false) : -1;
  }

  /**
   * 与 `start` 处引号配对的收尾引号的下标，会跳过双写的引号；
   * `backslashEscapes` 为真时还跳过被转义的字符。始终没有收尾时返回最后一个下标。
   */
  protected static closingQuote(sql: string, start: number, backslashEscapes: boolean): number {
    const quote = sql[start];
    for (let i = start + 1; i < sql.length; i++) {
      const c = sql[i];
      if (backslashEscapes && c === "\\") i++;
      else if (c === quote && sql[i + 1] === quote) i++;
      else if (c === quote) return i;
    }
    return sql.length - 1;
  }

  /** 供子类在构造函数里为函数注册本方言的渲染方式。 */
  protected register(fn: FunctionDef, renderer: FunctionRenderer): void {
    this.#functions.set(fn, renderer);
  }

  /** 本方言为函数注册的渲染器，没有注册时为 undefined。 */
  renderer(fn: FunctionDef): FunctionRenderer | undefined {
    return this.#functions.get(fn);
  }

  /** 方言名，便于日志和错误信息。 */
  toString(): string {
    return this.name;
  }
}
