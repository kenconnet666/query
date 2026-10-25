import { SqliteSchemaSyntax } from "./schema.js";
import {
  ADD_DAYS,
  ADD_MONTHS,
  ADD_SECONDS,
  ADD_YEARS,
  columnList,
  CURRENT_DATE,
  CURRENT_TIMESTAMP,
  DAY,
  DAYS_BETWEEN,
  Dialect,
  doNothing,
  FunctionDef,
  HOUR,
  LOCATE,
  MINUTE,
  MONTH,
  MONTH_START,
  renderOnAndWhere,
  SECOND,
  SECONDS_BETWEEN,
  YEAR,
  type CastType,
  type JoinWrite,
  type Lock,
  type Renderable,
  type SqlType,
  type SqlWriter,
  type Upsert,
} from "@zerodep-dev/query-core";

/** 需要编译选项才有的函数，以及对应的选项；每个数据库实例第一次渲染时警告一次。 */
const EXTENSIONS: Readonly<Record<string, string>> = {
  SQRT: "SQLITE_ENABLE_MATH_FUNCTIONS",
  POWER: "SQLITE_ENABLE_MATH_FUNCTIONS",
  CEIL: "SQLITE_ENABLE_MATH_FUNCTIONS",
  FLOOR: "SQLITE_ENABLE_MATH_FUNCTIONS",
  MOD: "SQLITE_ENABLE_MATH_FUNCTIONS",
  MEDIAN: "SQLITE_ENABLE_PERCENTILE",
  PERCENTILE_CONT: "SQLITE_ENABLE_PERCENTILE",
  PERCENTILE_DISC: "SQLITE_ENABLE_PERCENTILE",
};

/** 用公式代替的统计聚合，不能加 OVER (...)。 */
const FORMULAS = new Set(["STDDEV_SAMP", "STDDEV_POP", "VAR_SAMP", "VAR_POP"]);

/**
 * SQLite（3.46 起），node:sqlite 与 SQLite WASM 用的同一套写法，与 Java 的 `SqliteDialect` 一致。标识符不分大小写，原样书写。
 * 日期和时间戳按文本存储，布尔存成 0 / 1，小数按 NUMERIC 亲和存储；差异和取舍见 `docs/decisions/0009-sqlite-dialect.md`。
 */
export class SqliteDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new SqliteDialect();

  #schemaSyntax: SqliteSchemaSyntax | undefined;

  /** 结构变更的写法，见 `db.schema()`。 */
  override get schemaSyntax(): SqliteSchemaSyntax {
    return (this.#schemaSyntax ??= new SqliteSchemaSyntax(this));
  }

  readonly name = "SQLite";


  /** 有递归 CTE（WITH RECURSIVE）。 */

  override get supportsRecursiveCte(): boolean {

    return true;

  }

  /**
   * 登记与三库不同的写法：日期部分用 strftime，加日期用 date 的修饰符（月份加 floor，月末对齐），相隔天数用 julianday，
   * 相隔秒数用带 subsec 的 unixepoch；当前时间取本地时区；补齐用 zeroblob 拼出填充串；统计聚合用公式；GROUPING 报错。
   */
  protected constructor() {
    super();
    this.register(LOCATE, (w, args) => FunctionDef.call(w, "instr", args));
    this.register(YEAR, (w, args) => part(w, args, "%Y"));
    this.register(MONTH, (w, args) => part(w, args, "%m"));
    this.register(DAY, (w, args) => part(w, args, "%d"));
    this.register(HOUR, (w, args) => part(w, args, "%H"));
    this.register(MINUTE, (w, args) => part(w, args, "%M"));
    this.register(SECOND, (w, args) => part(w, args, "%S"));
    this.register(ADD_DAYS, (w, args) => shiftDate(w, args, " days", false));
    this.register(ADD_MONTHS, (w, args) => shiftDate(w, args, " months", true));
    this.register(ADD_YEARS, (w, args) => shiftDate(w, args, " years", true));
    this.register(DAYS_BETWEEN, (w, args) => void w.sql("CAST(julianday(").render(args[1]!).sql(") - julianday(").render(args[0]!).sql(") AS INTEGER)"));
    this.register(SECONDS_BETWEEN, (w, args) => void w.sql("CAST(unixepoch(").render(args[1]!).sql(", 'subsec') - unixepoch(").render(args[0]!).sql(", 'subsec') AS INTEGER)"));
    this.register(ADD_SECONDS, (w, args) => timestampText(w, { render: (x) => void x.render(args[0]!).sql(", ").render(args[1]!).sql(" || ' seconds'") }));
    this.register(MONTH_START, (w, args) => void w.sql("date(").render(args[0]!).sql(", 'start of month')"));
    this.register(CURRENT_DATE, (w) => void w.sql("date('now', 'localtime')"));
    this.register(CURRENT_TIMESTAMP, (w) => timestampText(w, { render: (x) => void x.sql("'now', 'localtime'") }));
    this.registerCall("LEFT", (w, args) => void w.sql("substr(").render(args[0]!).sql(", 1, ").render(args[1]!).sql(")"));
    this.registerCall("RIGHT", (w, args) => void w.sql("CASE WHEN ").render(args[1]!).sql(" > 0 THEN substr(").render(args[0]!).sql(", -").render(args[1]!).sql(") ELSE '' END"));
    this.registerCall("LPAD", (w, args) => pad(w, args, true));
    this.registerCall("RPAD", (w, args) => pad(w, args, false));
    this.registerCall("GROUPING", () => {
      throw new Error("SQLite has no GROUPING() or ROLLUP; group in a CTE per level and UNION ALL them");
    });
    for (const name of ["SQRT", "POWER", "CEIL", "FLOOR", "MOD"]) {
      this.registerCall(name, (w, args) => {
        this.warnOnce(w, name);
        FunctionDef.call(w, name.toLowerCase(), args);
      });
    }
    this.registerCall("VAR_SAMP", (w, args) => variance(w, args[0]!, true));
    this.registerCall("VAR_POP", (w, args) => variance(w, args[0]!, false));
    this.registerCall("STDDEV_SAMP", (w, args) => this.#deviation(w, args[0]!, true));
    this.registerCall("STDDEV_POP", (w, args) => this.#deviation(w, args[0]!, false));
  }

  /** 函数 name 需要编译选项时，每个数据库实例第一次渲染记一条警告；供方言专属函数使用。 */
  warnOnce(w: SqlWriter, name: string): void {
    const option = EXTENSIONS[name];
    if (option !== undefined) w.warnOnce(name, () => `${name}() needs SQLite built with ${option}; sqlite-jdbc, node:sqlite and the official WASM build have it`);
  }

  /** 标准差：方差开平方（sqrt 需要数学函数）。 */
  #deviation(w: SqlWriter, x: Renderable, sample: boolean): void {
    this.warnOnce(w, "SQRT");
    w.sql("sqrt(");
    variance(w, x, sample);
    w.sql(")");
  }

  /** 字母、数字、下划线组成的名字无需加引号。 */
  protected isBare(identifier: string): boolean {
    return /^[A-Za-z_][A-Za-z0-9_]*$/.test(identifier);
  }

  /** SQLite 的标识符不分大小写，原样书写。 */
  foldCase(identifier: string): string {
    return identifier;
  }

  /** UPDATE 和 DELETE 的表别名必须写 AS，查询里也照样写。 */
  override sourceAlias(w: SqlWriter, alias: string): void {
    w.sql(" AS ").alias(alias);
  }

  /** 只有 offset 时写成 `LIMIT -1 OFFSET m`：SQLite 的 OFFSET 必须跟在 LIMIT 后面。 */
  override limit(w: SqlWriter, limit: number | undefined, offset: number | undefined): void {
    if (limit === undefined && offset !== undefined && offset > 0) w.sql(`\nLIMIT -1 OFFSET ${offset}`);
    else super.limit(w, limit, offset);
  }

  /** SQLite 没有 ROLLUP。 */
  override groupBy(w: SqlWriter, keys: readonly Renderable[], rollup: boolean): void {
    if (rollup) throw new Error("SQLite has no ROLLUP; group in a CTE per level and UNION ALL them");
    super.groupBy(w, keys, false);
  }

  /** SQLite 只有一个写者，没有行锁；用写事务代替加锁读。 */
  override lock(_w: SqlWriter, _lock: Lock): void {
    throw new Error("SQLite has no FOR UPDATE: it has a single writer, so read and write inside one transaction instead");
  }

  /** NULL 也相等的比较写成 `a IS b` / `a IS NOT b`。 */
  override notDistinct(w: SqlWriter, left: Renderable, right: Renderable, negate: boolean): void {
    w.render(left).sql(negate ? " IS NOT " : " IS ").render(right);
  }

  /** 没有 INTERSECT ALL、EXCEPT ALL。 */
  override setOperator(operator: string): string {
    if (operator.endsWith(" ALL") && operator !== "UNION ALL") throw new Error(`SQLite has no ${operator}`);
    return operator;
  }

  /**
   * SQLite 没有 ANY / ALL 子查询：`x op ANY (q)` 写成 `EXISTS (SELECT 1 FROM (q) AS 别名 WHERE x op 别名.列)`，
   * `x op ALL (q)` 写成 `NOT EXISTS (... WHERE (x op 别名.列) IS NOT TRUE)`。用在 WHERE 里与标准写法等价。
   */
  override quantified(w: SqlWriter, left: Renderable, operator: string, quantifier: string, _subquery: Renderable, named: Renderable, column: string): void {
    const alias = w.aliasFor({}, "q");
    const all = quantifier === "ALL";
    w.sql(all ? "NOT EXISTS (SELECT 1 FROM (" : "EXISTS (SELECT 1 FROM (").render(named).sql(")");
    this.sourceAlias(w, alias);
    w.sql(" WHERE ");
    if (all) w.sql("(");
    w.render(left).sql(` ${operator} `).alias(alias).sql(".").identifier(column);
    if (all) w.sql(") IS NOT TRUE");
    w.sql(")");
  }

  /** 复合查询的一段不能加括号，一组写成 `SELECT * FROM (...)`。 */
  override get groupOpen(): string {
    return "SELECT * FROM (";
  }

  /** 公式代替的统计聚合不能当窗口函数用。 */
  override windowable(name: string): boolean {
    return !FORMULAS.has(name);
  }

  /** 日期和时间戳写成规整的文本（存的就是文本）。 */
  override literal(w: SqlWriter, value: unknown, type: SqlType<unknown>): void {
    if (value !== null && (type.kind === "date" || type.kind === "timestamp")) w.sql(this.stringLiteral(String(type.encode(value))));
    else super.literal(w, value, type);
  }

  /** 转换的类型名按 SQLite 的亲和：文本 TEXT、小数 NUMERIC、整数 INTEGER、浮点 REAL。 */
  override castType(type: CastType<unknown>): string {
    switch (type.kind) {
      case "text":
        return "TEXT";
      case "decimal":
        return "NUMERIC";
      case "double":
        return "REAL";
      case "date":
      case "timestamp":
        throw new Error("dates are converted by cast(...)");
      default:
        return "INTEGER";
    }
  }

  /** 转成日期写成 `date(x)`、转成时间戳写成规整的时间戳文本：`CAST(x AS DATE)` 在 SQLite 上得到数字。 */
  override cast(w: SqlWriter, inner: Renderable, target: CastType<unknown>): void {
    if (target.kind === "date") w.sql("date(").render(inner).sql(")");
    else if (target.kind === "timestamp") timestampText(w, inner);
    else super.cast(w, inner, target);
  }

  /** 除法先把被除数转成 REAL：小数列里的整数值存成 INTEGER，`7 / 2` 会得到 3。 */
  override arithmetic(w: SqlWriter, operator: string, operands: readonly Renderable[]): void {
    if (operator !== "/") {
      super.arithmetic(w, operator, operands);
      return;
    }
    w.sql("(CAST(").render(operands[0]!).sql(" AS REAL)");
    for (const r of operands.slice(1)) w.sql(" / ").render(r);
    w.sql(")");
  }

  /** `INSERT ... ON CONFLICT (键) DO UPDATE SET 列 = excluded.列` 或 `DO NOTHING`。 */
  override upsert(w: SqlWriter, u: Upsert): void {
    w.sql("INSERT INTO ").render(u.table).sql(" ");
    columnList(w, u.columns);
    w.sql(" VALUES (").join(u.values, ", ").sql(")\nON CONFLICT ");
    columnList(w, u.keys);
    if (doNothing(u)) {
      w.sql(" DO NOTHING");
      return;
    }
    w.sql(" DO UPDATE SET ");
    const sets = [...u.updates.map((c) => (x: SqlWriter) => x.identifier(c).sql(" = excluded.").identifier(c)), ...u.assignments.map(([c, v]) => (x: SqlWriter) => x.identifier(c).sql(" = ").render(v))];
    sets.forEach((set, i) => {
      if (i > 0) w.sql(", ");
      set(w);
    });
  }

  /** 插入后用 RETURNING 取回生成的值。 */
  override returning(column: string): string {
    return ` RETURNING ${this.quote(column)}`;
  }

  /** `UPDATE 表 AS 别名 SET 列 = 值 FROM 连接表 AS 别名 WHERE 连接条件 AND ...`；SET 左边的列不带别名。 */
  override joinUpdate(w: SqlWriter, u: JoinWrite): void {
    w.sql("UPDATE ").render(u.target).sql("\nSET ");
    u.columns.forEach((c, i) => {
      if (i > 0) w.sql(", ");
      w.render(c).sql(" = ").render(u.values[i]!);
    });
    w.sql("\nFROM ").render(u.source).sql("\nWHERE ");
    renderOnAndWhere(w, u);
  }

  /** SQLite 的 DELETE 不能连接别的表，写成 `DELETE FROM 表 AS 别名 WHERE EXISTS (SELECT 1 FROM 连接表 WHERE ...)`。 */
  override joinDelete(w: SqlWriter, d: JoinWrite): void {
    w.sql("DELETE FROM ").render(d.target).sql("\nWHERE EXISTS (SELECT 1\nFROM ").render(d.source).sql("\nWHERE ");
    renderOnAndWhere(w, d);
    w.sql(")");
  }

  /** 绑定参数的上限（3.32 起的 SQLITE_MAX_VARIABLE_NUMBER）。 */
  override get maxParameters(): number {
    return 32766;
  }

  /** SQLITE_INTERRUPT：语句被中断（WASM 和 node:sqlite 都不能按时取消，这里只识别中断）。 */
  override isTimeout(error: unknown): boolean {
    return String((error as { message?: unknown } | null)?.message ?? "").includes("SQLITE_INTERRUPT");
  }
}

/** 写出 `CAST(strftime('格式', e) AS INTEGER)`。 */
function part(w: SqlWriter, args: readonly Renderable[], format: string): void {
  w.sql(`CAST(strftime('${format}', `).render(args[0]!).sql(") AS INTEGER)");
}

/** 写出 `date(d, n || ' unit'[, 'floor'])`；floor 让月末对齐（1 月 31 日加一个月是 2 月 28 日）。 */
function shiftDate(w: SqlWriter, args: readonly Renderable[], unit: string, floor: boolean): void {
  w.sql("date(").render(args[0]!).sql(", ").render(args[1]!).sql(` || '${unit}'`).sql(floor ? ", 'floor')" : ")");
}

/** 写出规整的时间戳文本：`strftime('%Y-%m-%d %H:%M:%f', ...)` 再去掉小数末尾的零和多余的小数点。 */
function timestampText(w: SqlWriter, args: Renderable): void {
  w.sql("rtrim(rtrim(strftime('%Y-%m-%d %H:%M:%f', ").render(args).sql("), '0'), '.')");
}

/** 补齐到 length：已经不短于 length 时截断，否则用填充串补在左边（lpad）或右边。 */
function pad(w: SqlWriter, args: readonly Renderable[], left: boolean): void {
  const [e, n, p] = [args[0]!, args[1]!, args[2]!];
  const fill: Renderable = { render: (x) => void x.sql("substr(replace(hex(zeroblob(").render(n).sql(")), '00', ").render(p).sql("), 1, ").render(n).sql(" - length(").render(e).sql("))") };
  w.sql("CASE WHEN length(").render(e).sql(") >= ").render(n).sql(" THEN substr(").render(e).sql(", 1, ").render(n).sql(") ELSE ");
  if (left) w.render(fill).sql(" || ").render(e);
  else w.render(e).sql(" || ").render(fill);
  w.sql(" END");
}

/** 方差的公式 `(SUM(x*x) - SUM(x)*SUM(x)/COUNT(x)) / (COUNT(x) - 1)`（总体方差除以 COUNT(x)），不小于 0。 */
function variance(w: SqlWriter, x: Renderable, sample: boolean): void {
  w.sql("MAX((SUM(").render(x).sql(" * ").render(x).sql(") - SUM(").render(x).sql(") * SUM(").render(x).sql(") / COUNT(").render(x).sql(")) / ");
  if (sample) w.sql("(COUNT(").render(x).sql(") - 1)");
  else w.sql("COUNT(").render(x).sql(")");
  w.sql(", 0)");
}
