import { Dialect, type CastType } from "@query/core";

/** SAP HANA 2.0. Unquoted identifiers fold to upper case. */
export class HanaDialect extends Dialect {
  static readonly instance = new HanaDialect();

  readonly name = "SAP HANA";

  protected constructor() {
    super();
  }

  protected isBare(identifier: string): boolean {
    return /^[A-Z_][A-Z0-9_#$]*$/.test(identifier);
  }

  foldCase(identifier: string): string {
    return identifier.toUpperCase();
  }

  /** N'...' keeps non-ASCII text intact. */
  protected override stringLiteral(s: string): string {
    const quoted = `'${s.replaceAll("'", "''")}'`;
    return /^[\x00-\x7f]*$/.test(s) ? quoted : "N" + quoted;
  }

  override castType(type: CastType<unknown>): string {
    if (type.kind === "text") return `NVARCHAR(${type.length})`;
    if (type.kind === "double") return "DOUBLE";
    return super.castType(type);
  }
}
