import { MysqlSchemaSyntax } from "./schema.js";
import { ADD_DAYS, ADD_MONTHS, ADD_SECONDS, ADD_YEARS, columnList, CONCAT, DAYS_BETWEEN, Dialect, doNothing, FunctionDef, LOCATE, MONTH_START, SECOND, SECONDS_BETWEEN, STRING_AGG, type CastType, type Clause, type JoinWrite, type Renderable, type SqlWriter, type Upsert } from "@zerodep-dev/query-core";

/**
 * 默认 SQL 模式下的 MySQL 8。标识符一律用反引号引用（RANK 等窗口函数名是保留字），
 * `||` 不是拼接运算符，字符串字面量里的反斜杠是转义字符。
 */
export class MysqlDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new MysqlDialect();

  #schemaSyntax: MysqlSchemaSyntax | undefined;

  /** 结构变更的写法，见 `db.schema()`。 */
  override get schemaSyntax(): MysqlSchemaSyntax {
    return (this.#schemaSyntax ??= new MysqlSchemaSyntax(this));
  }

  readonly name: string = "MySQL";

  /**
   * `||` 不是拼接，CONCAT 改写为函数；LOCATE 的参数顺序与核心定义相反，需要对调；
   * 日期加减写成 DATE_ADD，相隔天数写成 DATEDIFF(to, from)，相隔秒数写成 TIMESTAMPDIFF，STRING_AGG 写成 GROUP_CONCAT。
   */
  protected constructor() {
    super();
    this.register(CONCAT, (w, args) => FunctionDef.call(w, "CONCAT", args));
    this.register(LOCATE, (w, args) => FunctionDef.call(w, "LOCATE", [args[1]!, args[0]!]));
    this.register(ADD_DAYS, (w, args) => dateAdd(w, args, "DAY"));
    this.register(ADD_MONTHS, (w, args) => dateAdd(w, args, "MONTH"));
    this.register(ADD_YEARS, (w, args) => dateAdd(w, args, "YEAR"));
    this.register(DAYS_BETWEEN, (w, args) => FunctionDef.call(w, "DATEDIFF", [args[1]!, args[0]!]));
    this.register(SECOND, (w, args) => FunctionDef.call(w, "SECOND", args));
    this.register(ADD_SECONDS, (w, args) => void w.sql("TIMESTAMPADD(SECOND, ").render(args[1]!).sql(", ").render(args[0]!).sql(")"));
    this.register(MONTH_START, (w, args) => void w.sql("DATE_SUB(").render(args[0]!).sql(", INTERVAL DAYOFMONTH(").render(args[0]!).sql(") - 1 DAY)"));
    this.register(SECONDS_BETWEEN, (w, args) => void w.sql("TIMESTAMPDIFF(SECOND, ").render(args[0]!).sql(", ").render(args[1]!).sql(")"));
    this.register(STRING_AGG, (w, args) => {
      w.sql("GROUP_CONCAT(").render(args[0]!);
      if (args.length > 2) w.sql(" ORDER BY ").join(args.slice(2), ", ");
      w.sql(" SEPARATOR ").render(args[1]!).sql(")");
    });
  }

  /**
   * `INSERT ... VALUES (...) AS new ON DUPLICATE KEY UPDATE 列 = new.列`（8.0.19 起的行别名写法）；冲突了什么也不做时
   * 把第一个键设为它自己，而不用会把别的错误也降为警告的 INSERT IGNORE。冲突由表的主键和唯一索引决定。
   */
  /** `UPDATE 表 别名 JOIN 连接表 别名 ON ... SET 别名.列 = 值 WHERE ...`；SET 左边带别名，两张表有同名列时才不含糊。 */
  override joinUpdate(w: SqlWriter, u: JoinWrite): void {
    w.sql("UPDATE ").render(u.target).sql("\nJOIN ").render(u.source).sql(" ON ").render(u.on).sql("\nSET ");
    u.targetColumns.forEach((c, i) => {
      if (i > 0) w.sql(", ");
      w.render(c).sql(" = ").render(u.values[i]!);
    });
    if (u.where !== undefined) w.sql("\nWHERE ").render(u.where);
  }

  /** `DELETE 别名 FROM 表 别名 JOIN 连接表 别名 ON ... WHERE ...`；只删被改表的行。 */
  override joinDelete(w: SqlWriter, d: JoinWrite): void {
    w.sql("DELETE ").render(d.targetAlias).sql(" FROM ").render(d.target).sql("\nJOIN ").render(d.source).sql(" ON ").render(d.on);
    if (d.where !== undefined) w.sql("\nWHERE ").render(d.where);
  }

  override upsert(w: SqlWriter, u: Upsert): void {
    w.sql("INSERT INTO ").render(u.table).sql(" ");
    columnList(w, u.columns);
    w.sql(" VALUES (").join(u.values, ", ").sql(")");
    if (doNothing(u)) {
      w.sql("\nON DUPLICATE KEY UPDATE ").identifier(u.keys[0]!).sql(" = ").identifier(u.keys[0]!);
      return;
    }
    w.sql(" AS ").identifier("new").sql("\nON DUPLICATE KEY UPDATE ");
    const sets = [...u.updates.map((c) => (x: SqlWriter) => x.identifier(c).sql(" = ").identifier("new").sql(".").identifier(c)), ...u.assignments.map(([c, v]) => (x: SqlWriter) => x.identifier(c).sql(" = ").render(v))];
    sets.forEach((set, i) => {
      if (i > 0) w.sql(", ");
      set(w);
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

  /** NULL 也相等的比较写成 MySQL 的 `<=>`，否定时加 NOT。 */
  override notDistinct(w: SqlWriter, left: Renderable, right: Renderable, negate: boolean): void {
    if (negate) w.sql("NOT (").render(left).sql(" <=> ").render(right).sql(")");
    else w.render(left).sql(" <=> ").render(right);
  }

  /** MySQL 只认 SELECT 后面第一个优化器提示注释，所以把这一段的提示合并进一个 `/*+ ... *\/`，其他子句照常写出。 */
  override hints(w: SqlWriter, hints: readonly Clause[]): void {
    const optimizer = hints.filter((h): h is Hint => h instanceof Hint);
    if (optimizer.length > 0) {
      w.sql("/*+ ");
      optimizer.forEach((h, i) => {
        if (i > 0) w.sql(" ");
        h.body(w);
      });
      w.sql(" */ ");
    }
    for (const c of hints) if (!(c instanceof Hint)) w.render(c).sql(" ");
  }

  /** MySQL 没有 FULL JOIN。 */
  /** MySQL 8 有递归 CTE（WITH RECURSIVE），层数受 cte_max_recursion_depth 限制。 */
  override get supportsRecursiveCte(): boolean {
    return true;
  }

  /** MySQL 8.0.14 起有 LATERAL 派生表。 */
  override get supportsLateral(): boolean {
    return true;
  }

  override get supportsFullJoin(): boolean {
    return false;
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

/**
 * 一个优化器提示：MySQL 只认 SELECT 后面第一个 `/*+ ... *\/` 注释，所以同一段查询的提示由方言合并进一个注释。
 * 由 {@link maxExecutionTime}、{@link index} 等创建；兼容 MySQL 的方言（PolarDB）用第二个参数造只属于自己的提示，照样合并。
 */
export class Hint implements Clause {
  readonly position = "hint";

  constructor(
    readonly body: (w: SqlWriter) => void,
    /** 只能在这个方言及其子类上渲染。 */
    readonly dialect: typeof MysqlDialect = MysqlDialect,
  ) {}

  /** 单独写出时带上注释符号；同一段里有多个提示时由方言合并。 */
  render(w: SqlWriter): void {
    w.sql("/*+ ");
    this.body(w);
    w.sql(" */");
  }
}

