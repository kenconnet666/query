import { ADD_DAYS, ADD_MONTHS, ADD_YEARS, DAY, DAYS_BETWEEN, Dialect, FunctionDef, LOCATE, MONTH, SECONDS_BETWEEN, YEAR, type CastType, type Renderable, type SqlWriter } from "@zerodep-dev/query-core";

/** PostgreSQL。未加引号的标识符折叠为小写；参数带编号（$1、$2……）。 */
export class PostgresDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new PostgresDialect();

  readonly name = "PostgreSQL";

  /**
   * 没有 LOCATE，改写为 STRPOS；日期部分写成 EXTRACT 再转成整数（EXTRACT 返回 numeric），
   * 加天数是 `date + n`，加月和年乘以 INTERVAL 后转回 DATE，相隔天数是两个日期相减，
   * 相隔秒数取两个时间戳之差的 EPOCH 再舍去小数（直接转 BIGINT 会四舍五入）。
   */
  protected constructor() {
    super();
    this.register(LOCATE, (w, args) => FunctionDef.call(w, "STRPOS", args));
    this.register(YEAR, (w, args) => extract(w, args, "YEAR"));
    this.register(MONTH, (w, args) => extract(w, args, "MONTH"));
    this.register(DAY, (w, args) => extract(w, args, "DAY"));
    this.register(ADD_DAYS, (w, args) => void w.sql("(").render(args[0]!).sql(" + ").render(args[1]!).sql(")"));
    this.register(ADD_MONTHS, (w, args) => addInterval(w, args, "month"));
    this.register(ADD_YEARS, (w, args) => addInterval(w, args, "year"));
    this.register(DAYS_BETWEEN, (w, args) => void w.sql("(").render(args[1]!).sql(" - ").render(args[0]!).sql(")"));
    this.register(SECONDS_BETWEEN, (w, args) => void w.sql("CAST(TRUNC(EXTRACT(EPOCH FROM (").render(args[1]!).sql(" - ").render(args[0]!).sql("))) AS BIGINT)"));
  }

  /** 插入后用 RETURNING 取回生成的值。 */
  override returning(column: string): string {
    return ` RETURNING ${this.quote(column)}`;
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

/** 写出 `CAST(EXTRACT(FIELD FROM e) AS INTEGER)`。 */
function extract(w: SqlWriter, args: readonly Renderable[], field: string): void {
  w.sql(`CAST(EXTRACT(${field} FROM `).render(args[0]!).sql(") AS INTEGER)");
}

/** 写出 `CAST(date + n * INTERVAL '1 unit' AS DATE)`。 */
function addInterval(w: SqlWriter, args: readonly Renderable[], unit: string): void {
  w.sql("CAST(").render(args[0]!).sql(" + ").render(args[1]!).sql(` * INTERVAL '1 ${unit}' AS DATE)`);
}
