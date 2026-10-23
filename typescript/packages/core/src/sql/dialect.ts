import type { CastType, SqlType } from "./types.js";
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

  /** 没有表的 SELECT 需要的部分：多数数据库什么都不要，HANA 要 `FROM DUMMY`。 */
  fromNothing(_w: SqlWriter): void {}

  /** 第 n 个参数的占位符，n 从 1 开始计数。 */
  placeholder(_index: number): string {
    return "?";
  }

  /** 写出分页子句 LIMIT / OFFSET；没有 limit 时 OFFSET 单独成行。 */
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
