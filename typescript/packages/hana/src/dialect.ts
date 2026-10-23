import { Dialect, type CastType, type SqlWriter } from "@zerodep-dev/query-core";

/** SAP HANA 2.0。未加引号的标识符折叠为大写。 */
export class HanaDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new HanaDialect();

  readonly name = "SAP HANA";

  /** 插入后在同一个连接上查这个连接最近一次生成的 IDENTITY 值；表上有会插入别的 IDENTITY 表的触发器时取到的是触发器那一次的。 */
  override get identityQuery(): string {
    return "SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY";
  }

  protected constructor() {
    super();
  }

  /** 只有大写字母、数字、下划线、# 和 $ 构成的名称可以不加引号。 */
  protected isBare(identifier: string): boolean {
    return /^[A-Z_][A-Z0-9_#$]*$/.test(identifier);
  }

  /** 未加引号的标识符折叠为大写。 */
  foldCase(identifier: string): string {
    return identifier.toUpperCase();
  }

  /** HANA 没有无 FROM 的 SELECT；DUMMY 是它的单行表。 */
  override fromNothing(w: SqlWriter): void {
    w.sql("\nFROM DUMMY");
  }

  /** 613：执行因超时而中止。 */
  override isTimeout(error: unknown): boolean {
    return HanaDialect.errorProperty(error, "code") === 613;
  }

  /** 含非 ASCII 字符的文本写成 N'...'，以免内容受损。 */
  protected override stringLiteral(s: string): string {
    const quoted = `'${s.replaceAll("'", "''")}'`;
    return /^[\x00-\x7f]*$/.test(s) ? quoted : "N" + quoted;
  }

  /** 文本用 NVARCHAR、双精度用 DOUBLE，其余用通用写法。 */
  override castType(type: CastType<unknown>): string {
    if (type.kind === "text") return `NVARCHAR(${type.length})`;
    if (type.kind === "double") return "DOUBLE";
    return super.castType(type);
  }
}
