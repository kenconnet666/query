import type { CastType, SqlType } from "./types.js";
import type { Renderable, SqlWriter } from "./writer.js";

/** Writes one function call. */
export type FunctionRenderer = (w: SqlWriter, args: readonly Renderable[]) => void;

/** A dialect class, as `FunctionDef.only` and clauses name their owner; its constructor may be protected. */
export type DialectClass = Function & { readonly prototype: Dialect };

/**
 * A function's identity. Rendering is looked up in order: the database instance's overrides, the
 * dialect's registrations, then this definition's standard rendering. A definition owned by one
 * dialect refuses to render on another instead of guessing an equivalent.
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

  /** A function written NAME(arg, ...) on every database, unless a standard rendering is given. */
  static of(name: string, standard?: FunctionRenderer): FunctionDef {
    return new FunctionDef(name, standard, undefined);
  }

  /** A function that exists only on one dialect (or its subclasses). */
  static only(owner: DialectClass, name: string, standard?: FunctionRenderer): FunctionDef {
    return new FunctionDef(name, standard, owner);
  }

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

  /** Writes NAME(arg1, arg2, ...). */
  static call(w: SqlWriter, name: string, args: readonly Renderable[]): void {
    w.sql(name).sql("(").join(args, ", ").sql(")");
  }

  toString(): string {
    return this.name;
  }
}

/**
 * A dialect-only piece of a SELECT statement, such as HANA's `WITH HINT(...)`, PostgreSQL's
 * `DISTINCT ON (...)` or a MySQL optimizer hint. Rendering on another dialect is rejected.
 */
export interface Clause extends Renderable {
  /** `hint` right after SELECT, `distinct` in place of DISTINCT, `end` after ORDER BY and LIMIT. */
  readonly position: "hint" | "distinct" | "end";
  /** The dialect this clause belongs to; absent for clauses valid everywhere. */
  readonly dialect?: DialectClass;
}

/**
 * An open base class for dialects. It owns only syntax: identifier quoting and case folding,
 * literals, placeholders, paging and CAST type names. Function renderings a dialect knows are
 * registered in its constructor with {@link register}; nothing switches on a dialect name.
 */
/** Kinds a dialect can write as literals; others stay parameters even when the writer inlines. */
const LITERAL_KINDS = new Set(["text", "integer", "bigint", "double", "decimal", "boolean", "date", "timestamp"]);

/** Number text a literal may carry as it is: the forms `decimal` accepts, all valid SQL numeric literals. */
const NUMBER_TEXT = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** Reserved words of standard SQL, PostgreSQL and HANA; see `Dialect.isReserved`. */
const RESERVED = new Set(
  `ALL ALTER ANALYSE ANALYZE AND ANY ARRAY AS ASC ASYMMETRIC AUTHORIZATION BEFORE BEGIN BETWEEN BINARY BOTH BY CASE CAST CHAR CHECK COLLATE COLLATION COLUMN CONCURRENTLY CONDITION CONNECT CONSTRAINT CREATE CROSS CUBE CURRENT_CATALOG CURRENT_DATE CURRENT_ROLE CURRENT_SCHEMA CURRENT_TIME CURRENT_TIMESTAMP CURRENT_USER CURSOR DECLARE DEFAULT DEFERRABLE DELETE DESC DISTINCT DO DROP ELSE ELSEIF END EXCEPT EXCEPTION EXEC EXISTS FALSE FETCH FOR FOREIGN FREEZE FROM FULL GRANT GROUP HAVING IF ILIKE IN INITIALLY INNER INOUT INSERT INTERSECT INTO IS ISNULL JOIN LATERAL LEADING LEFT LIKE LIMIT LOCALTIME LOCALTIMESTAMP LOOP MINUS NATURAL NCHAR NOT NOTNULL NULL OFFSET ON ONLY OR ORDER OUT OUTER OVERLAPS PLACING PRIMARY PRIOR REFERENCES RETURN RETURNING RETURNS RIGHT ROLLUP ROWID SELECT SESSION_USER SET SIMILAR SOME SQL START SYMMETRIC SYSTEM_USER TABLE TABLESAMPLE THEN TO TOP TRAILING TRUE UNION UNIQUE UNKNOWN UPDATE USER USING VALUES VARIADIC VERBOSE WHEN WHERE WHILE WINDOW WITH`.split(" "),
);

export abstract class Dialect {
  readonly #functions = new Map<FunctionDef, FunctionRenderer>();

  abstract readonly name: string;

  /** True when the identifier needs no quotes, e.g. HANA's upper-case names. */
  protected abstract isBare(identifier: string): boolean;

  /** The case unquoted identifiers fold to, so generated aliases stay unquoted. */
  abstract foldCase(identifier: string): string;

  /**
   * Words that must be quoted even in bare form, such as an entity `Order` aliased `order`.
   * Reserved words of standard SQL, PostgreSQL and HANA together; quoting one more word is harmless.
   */
  protected isReserved(identifier: string): boolean {
    return RESERVED.has(identifier.toUpperCase());
  }

  quote(identifier: string): string {
    return this.isBare(identifier) && !this.isReserved(identifier) ? identifier : `"${identifier.replaceAll('"', '""')}"`;
  }

  /** Aliases come from {@link SqlWriter.aliasFor}, already case-folded. */
  alias(alias: string): string {
    return this.quote(alias);
  }

  /** What a SELECT without a table needs: nothing on most databases, `FROM DUMMY` on HANA. */
  fromNothing(_w: SqlWriter): void {}

  /** The placeholder of the n-th parameter, counted from 1. */
  placeholder(_index: number): string {
    return "?";
  }

  limit(w: SqlWriter, limit: number | undefined, offset: number | undefined): void {
    if (limit !== undefined) w.sql(`\nLIMIT ${limit}`);
    if (offset !== undefined && offset > 0) w.sql(`${limit === undefined ? "\nOFFSET " : " OFFSET "}${offset}`);
  }

  /** The GROUP BY list; with rollup, subtotals for every prefix of the keys and a grand total. */
  groupBy(w: SqlWriter, keys: readonly Renderable[], rollup: boolean): void {
    if (rollup) w.sql("ROLLUP(").join(keys, ", ").sql(")");
    else w.join(keys, ", ");
  }

  /** One ORDER BY item; `nullsFirst` is undefined when the query does not specify NULL placement. */
  orderItem(w: SqlWriter, expr: Renderable, descending: boolean, nullsFirst: boolean | undefined): void {
    w.render(expr).sql(descending ? " DESC" : " ASC");
    if (nullsFirst !== undefined) w.sql(nullsFirst ? " NULLS FIRST" : " NULLS LAST");
  }

  /** Whether {@link literal} can write the value; others, such as NaN, stay parameters even when inlining. */
  hasLiteral(value: unknown, type: SqlType<unknown>): boolean {
    if (value === null) return true;
    if (!LITERAL_KINDS.has(type.kind)) return false;
    const stored = type.encode(value);
    return typeof stored !== "number" || Number.isFinite(stored);
  }

  /** A literal of the value's stored form; converters apply first. */
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

  protected stringLiteral(s: string): string {
    return `'${s.replaceAll("'", "''")}'`;
  }

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

  /** Longer IN lists are split into several IN predicates joined by OR. */
  get maxInListSize(): number {
    return 1000;
  }

  /** Bound parameters one statement may carry; the wire protocols of HANA, PostgreSQL and MySQL count them in 16 bits. */
  get maxParameters(): number {
    return 65535;
  }

  /** Whether a driver error means the database cancelled the statement for running past its timeout. */
  isTimeout(_error: unknown): boolean {
    return false;
  }

  /** The `code` (or another property) a driver puts on its errors, if any. */
  protected static errorProperty(error: unknown, name: string): unknown {
    return typeof error === "object" && error !== null ? (error as Record<string, unknown>)[name] : undefined;
  }

  /**
   * Where a quoted part of hand-written SQL that starts at `start` ends: the index of its last
   * character, or -1 when none starts there. Native queries look for `:name` only outside such parts.
   * The default knows '...' and "..." with doubled quotes inside.
   */
  quotedEnd(sql: string, start: number): number {
    const c = sql[start];
    return c === "'" || c === '"' ? Dialect.closingQuote(sql, start, false) : -1;
  }

  /**
   * The index of the quote closing the one at `start`, past doubled quotes and, with
   * `backslashEscapes`, past escaped characters; the last index when it never closes.
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

  protected register(fn: FunctionDef, renderer: FunctionRenderer): void {
    this.#functions.set(fn, renderer);
  }

  renderer(fn: FunctionDef): FunctionRenderer | undefined {
    return this.#functions.get(fn);
  }

  toString(): string {
    return this.name;
  }
}
