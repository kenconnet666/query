import { Dialect, FunctionDef, LOCATE, type CastType } from "@query/core";

/** PostgreSQL. Unquoted identifiers fold to lower case; parameters are numbered. */
export class PostgresDialect extends Dialect {
  static readonly instance = new PostgresDialect();

  readonly name = "PostgreSQL";

  protected constructor() {
    super();
    this.register(LOCATE, (w, args) => FunctionDef.call(w, "STRPOS", args));
  }

  protected isBare(identifier: string): boolean {
    return /^[a-z_][a-z0-9_$]*$/.test(identifier);
  }

  foldCase(identifier: string): string {
    return identifier.toLowerCase();
  }

  override placeholder(index: number): string {
    return `$${index}`;
  }

  /** 57014: query_canceled, which statement_timeout raises. */
  override isTimeout(error: unknown): boolean {
    return PostgresDialect.errorProperty(error, "code") === "57014";
  }

  /**
   * With standard_conforming_strings off, a backslash in '...' escapes the next character, so a
   * value could end the literal early. E'...' reads backslashes the same way under either setting.
   */
  protected override stringLiteral(s: string): string {
    return s.includes("\\") ? `E'${s.replaceAll("\\", "\\\\").replaceAll("'", "''")}'` : super.stringLiteral(s);
  }

  override castType(type: CastType<unknown>): string {
    if (type.kind === "decimal") return `NUMERIC(${type.length},${type.scale})`;
    return super.castType(type);
  }

  /** Also $$...$$ and $tag$...$tag$, as in function bodies; $1 or a $ inside a name starts none. */
  override quotedEnd(sql: string, start: number): number {
    if (sql[start] !== "$") return super.quotedEnd(sql, start);
    if (start > 0 && NAME_PART.test(sql[start - 1]!)) return -1;
    DOLLAR_TAG.lastIndex = start;
    const tag = DOLLAR_TAG.exec(sql)?.[0];
    if (tag === undefined) return -1;
    const close = sql.indexOf(tag, start + tag.length);
    return close < 0 ? sql.length - 1 : close + tag.length - 1;
  }
}

/** A dollar-quote delimiter: the tag follows the rules of a name, without dollar signs. */
const DOLLAR_TAG = /\$(?:[\p{L}_][\p{L}\p{N}_]*)?\$/uy;
const NAME_PART = /[\p{L}\p{N}_$]/u;
