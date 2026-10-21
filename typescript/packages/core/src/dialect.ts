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
export abstract class Dialect {
  readonly #functions = new Map<FunctionDef, FunctionRenderer>();

  abstract readonly name: string;

  /** True when the identifier needs no quotes, e.g. HANA's upper-case names. */
  protected abstract isBare(identifier: string): boolean;

  /** The case unquoted identifiers fold to, so generated aliases stay unquoted. */
  abstract foldCase(identifier: string): string;

  quote(identifier: string): string {
    return this.isBare(identifier) ? identifier : `"${identifier.replaceAll('"', '""')}"`;
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

  /** A literal of the value's stored form; converters apply first. */
  literal(w: SqlWriter, value: unknown, type: SqlType<unknown>): void {
    if (value === null) {
      w.sql("NULL");
      return;
    }
    const stored = type.encode(value);
    switch (type.kind) {
      case "text":
        w.sql(this.stringLiteral(String(stored)));
        return;
      case "integer":
      case "bigint":
      case "double":
      case "decimal":
        if (typeof stored !== "number" && typeof stored !== "bigint" && !(typeof stored === "string" && stored.trim() !== "" && !Number.isNaN(Number(stored)))) {
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
