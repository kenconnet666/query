import { CONCAT, Dialect, FunctionDef, type CastType, type Renderable, type SqlWriter } from "@query/core";

/**
 * MySQL 8 with its default SQL mode. Identifiers are always quoted with backticks (window function
 * names such as RANK are reserved words), `||` is not concatenation, and backslashes in string
 * literals are escapes.
 */
export class MysqlDialect extends Dialect {
  static readonly instance = new MysqlDialect();

  readonly name = "MySQL";

  protected constructor() {
    super();
    this.register(CONCAT, (w, args) => FunctionDef.call(w, "CONCAT", args));
  }

  protected isBare(): boolean {
    return false;
  }

  foldCase(identifier: string): string {
    return identifier;
  }

  override quote(identifier: string): string {
    return `\`${identifier.replaceAll("`", "``")}\``;
  }

  protected override stringLiteral(s: string): string {
    return `'${s.replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
  }

  /** MySQL needs LIMIT before OFFSET; the documented way to say "no limit" is the largest BIGINT UNSIGNED. */
  override limit(w: SqlWriter, limit: number | undefined, offset: number | undefined): void {
    const hasOffset = offset !== undefined && offset > 0;
    if (limit === undefined && !hasOffset) return;
    w.sql(`\nLIMIT ${limit ?? "18446744073709551615"}`);
    if (hasOffset) w.sql(` OFFSET ${offset}`);
  }

  /** No NULLS FIRST/LAST in MySQL: sort on "is null" first, which places NULLs the same way. */
  override orderItem(w: SqlWriter, expr: Renderable, descending: boolean, nullsFirst: boolean | undefined): void {
    if (nullsFirst !== undefined) w.render(expr).sql(nullsFirst ? " IS NULL DESC, " : " IS NULL ASC, ");
    w.render(expr).sql(descending ? " DESC" : " ASC");
  }

  override castType(type: CastType<unknown>): string {
    switch (type.kind) {
      case "text":
        return `CHAR(${type.length})`;
      case "integer":
      case "bigint":
        return "SIGNED";
      case "double":
        return "DOUBLE";
      case "timestamp":
        return "DATETIME(6)";
      case "boolean":
        throw new Error("MySQL has no CAST to BOOLEAN; compare the value instead");
      default:
        return super.castType(type);
    }
  }
}
