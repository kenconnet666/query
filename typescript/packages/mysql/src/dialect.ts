import { ADD_DAYS, ADD_MONTHS, ADD_YEARS, CONCAT, DAYS_BETWEEN, Dialect, FunctionDef, LOCATE, STRING_AGG, type CastType, type Renderable, type SqlWriter } from "@zerodep-dev/query-core";

/**
 * 默认 SQL 模式下的 MySQL 8。标识符一律用反引号引用（RANK 等窗口函数名是保留字），
 * `||` 不是拼接运算符，字符串字面量里的反斜杠是转义字符。
 */
export class MysqlDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new MysqlDialect();

  readonly name = "MySQL";

  /**
   * `||` 不是拼接，CONCAT 改写为函数；LOCATE 的参数顺序与核心定义相反，需要对调；
   * 日期加减写成 DATE_ADD，相隔天数写成 DATEDIFF(to, from)，STRING_AGG 写成 GROUP_CONCAT。
   */
  protected constructor() {
    super();
    this.register(CONCAT, (w, args) => FunctionDef.call(w, "CONCAT", args));
    this.register(LOCATE, (w, args) => FunctionDef.call(w, "LOCATE", [args[1]!, args[0]!]));
    this.register(ADD_DAYS, (w, args) => dateAdd(w, args, "DAY"));
    this.register(ADD_MONTHS, (w, args) => dateAdd(w, args, "MONTH"));
    this.register(ADD_YEARS, (w, args) => dateAdd(w, args, "YEAR"));
    this.register(DAYS_BETWEEN, (w, args) => FunctionDef.call(w, "DATEDIFF", [args[1]!, args[0]!]));
    this.register(STRING_AGG, (w, args) => {
      w.sql("GROUP_CONCAT(").render(args[0]!);
      if (args.length > 2) w.sql(" ORDER BY ").join(args.slice(2), ", ");
      w.sql(" SEPARATOR ").render(args[1]!).sql(")");
    });
  }

  /** 标识符一律加引号，所以没有可省略引号的名称。 */
  protected isBare(): boolean {
    return false;
  }

  /** 别名原样保留大小写。 */
  foldCase(identifier: string): string {
    return identifier;
  }

  /** 用反引号引用，内部的反引号加倍。 */
  override quote(identifier: string): string {
    return `\`${identifier.replaceAll("`", "``")}\``;
  }

  /** 名称也可以用反引号引用；字符串里的反斜杠会转义下一个字符。 */
  override quotedEnd(sql: string, start: number): number {
    const c = sql[start];
    if (c === "'" || c === '"') return MysqlDialect.closingQuote(sql, start, true);
    return c === "`" ? MysqlDialect.closingQuote(sql, start, false) : -1;
  }

  /** 3024：超过语句的最大执行时间。 */
  override isTimeout(error: unknown): boolean {
    return MysqlDialect.errorProperty(error, "errno") === 3024;
  }

  /** 反斜杠是转义字符，所以要加倍；单引号照常加倍。 */
  protected override stringLiteral(s: string): string {
    return `'${s.replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
  }

  /** MySQL 的 OFFSET 必须跟在 LIMIT 后面；文档里表示“不限行数”的办法是用最大的 BIGINT UNSIGNED。 */
  override limit(w: SqlWriter, limit: number | undefined, offset: number | undefined): void {
    const hasOffset = offset !== undefined && offset > 0;
    if (limit === undefined && !hasOffset) return;
    w.sql(`\nLIMIT ${limit ?? "18446744073709551615"}`);
    if (hasOffset) w.sql(` OFFSET ${offset}`);
  }

  /** MySQL 把 ROLLUP 写成整个 GROUP BY 列表的后缀。 */
  override groupBy(w: SqlWriter, keys: readonly Renderable[], rollup: boolean): void {
    w.join(keys, ", ");
    if (rollup) w.sql(" WITH ROLLUP");
  }

  /** MySQL 没有 NULLS FIRST/LAST：先按“是否为 NULL”排序，效果相同。 */
  override orderItem(w: SqlWriter, expr: Renderable, descending: boolean, nullsFirst: boolean | undefined): void {
    if (nullsFirst !== undefined) w.render(expr).sql(nullsFirst ? " IS NULL DESC, " : " IS NULL ASC, ");
    w.render(expr).sql(descending ? " DESC" : " ASC");
  }

  /** MySQL 的 CAST 目标类型名；它不支持 CAST 成 BOOLEAN。 */
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

/** 写出 `DATE_ADD(date, INTERVAL n UNIT)`。 */
function dateAdd(w: SqlWriter, args: readonly Renderable[], unit: string): void {
  w.sql("DATE_ADD(").render(args[0]!).sql(", INTERVAL ").render(args[1]!).sql(` ${unit})`);
}
