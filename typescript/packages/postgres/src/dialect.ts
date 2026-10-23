import { Dialect, FunctionDef, LOCATE, type CastType } from "@zerodep-dev/query-core";

/** PostgreSQL。未加引号的标识符折叠为小写；参数带编号（$1、$2……）。 */
export class PostgresDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new PostgresDialect();

  readonly name = "PostgreSQL";

  protected constructor() {
    super();
    this.register(LOCATE, (w, args) => FunctionDef.call(w, "STRPOS", args));
  }

  /** 只有小写字母、数字、下划线和 $ 构成的名称可以不加引号。 */
  protected isBare(identifier: string): boolean {
    return /^[a-z_][a-z0-9_$]*$/.test(identifier);
  }

  /** 未加引号的标识符折叠为小写。 */
  foldCase(identifier: string): string {
    return identifier.toLowerCase();
  }

  /** 占位符是带编号的 `$n`。 */
  override placeholder(index: number): string {
    return `$${index}`;
  }

  /** 57014：query_canceled，statement_timeout 触发时抛出。 */
  override isTimeout(error: unknown): boolean {
    return PostgresDialect.errorProperty(error, "code") === "57014";
  }

  /**
   * standard_conforming_strings 关闭时，'...' 里的反斜杠会转义下一个字符，值就可能提前结束字面量。
   * E'...' 在两种设置下对反斜杠的处理一致，所以含反斜杠的文本写成 E'...'。
   */
  protected override stringLiteral(s: string): string {
    return s.includes("\\") ? `E'${s.replaceAll("\\", "\\\\").replaceAll("'", "''")}'` : super.stringLiteral(s);
  }

  /** decimal 用 NUMERIC，其余用通用写法。 */
  override castType(type: CastType<unknown>): string {
    if (type.kind === "decimal") return `NUMERIC(${type.length},${type.scale})`;
    return super.castType(type);
  }

  /** 另外识别 $$...$$ 和 $tag$...$tag$（函数体里常见）；$1 或名称内部的 $ 不开启引号部分。 */
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

/** 美元引号的定界符：标签遵循名称的规则，但不含美元符号。 */
const DOLLAR_TAG = /\$(?:[\p{L}_][\p{L}\p{N}_]*)?\$/uy;
/** 名称中可以出现的字符，用来排除名称内部的 $。 */
const NAME_PART = /[\p{L}\p{N}_$]/u;
