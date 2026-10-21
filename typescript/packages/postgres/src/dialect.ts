import { Dialect, type CastType } from "@query/core";

/** PostgreSQL. Unquoted identifiers fold to lower case; parameters are numbered. */
export class PostgresDialect extends Dialect {
  static readonly instance = new PostgresDialect();

  readonly name = "PostgreSQL";

  protected constructor() {
    super();
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

  override castType(type: CastType<unknown>): string {
    if (type.kind === "decimal") return `NUMERIC(${type.length},${type.scale})`;
    return super.castType(type);
  }
}
