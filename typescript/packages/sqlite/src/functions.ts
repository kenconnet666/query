import {
  addCondition,
  call,
  CastType,
  condition,
  double,
  FunctionDef,
  lit,
  param,
  text,
  type Expr,
  type ExprBigint,
  type ExprDecimal,
  type ExprNumber,
  type ExprOf,
  type ExprText,
  type NumericValue,
  type Renderable,
  type SqlType,
  type SqlWriter,
} from "@zerodep-dev/query-core";
import { SqliteDialect } from "./dialect.js";

/**
 * SQLite 专有的函数和条件；在其他方言上拒绝渲染。与 Java 的 `Sqlite` 门面一致：有主操作数的经由视图 {@link sqlite} 点出来，
 * `sqlite(l.text).glob("A*")`、`sqlite(l.quantity).median()`。
 */

/** 渲染时确认当前方言是 SQLite，否则报错。 */
function requireSqlite(w: SqlWriter, what: string): void {
  if (!(w.dialect instanceof SqliteDialect)) throw new Error(`${what} is a SQLite operator; this database uses ${w.dialect.name}`);
}

/** 需要编译选项的方言函数：第一次渲染时警告，再写成 `name(args)`。 */
function extension(key: string, name: string): FunctionDef {
  return FunctionDef.only(SqliteDialect, name, (w: SqlWriter, args: readonly Renderable[]) => {
    (w.dialect as SqliteDialect).warnOnce(w, key);
    FunctionDef.call(w, name, args);
  });
}

const MEDIAN = extension("MEDIAN", "median").aggregating();
const PERCENTILE_CONT = extension("PERCENTILE_CONT", "percentile_cont").aggregating();
const PERCENTILE_DISC = extension("PERCENTILE_DISC", "percentile_disc").aggregating();

/** 百分位数 p 必须在 0 到 1 之间。 */
function fraction(p: number): number {
  if (!(p >= 0 && p <= 1)) throw new RangeError(`A percentile is between 0 and 1, got ${p}`);
  return p;
}

// ---------- 视图 ----------

/** 文本表达式上 SQLite 专有的方法。 */
export function sqlite<T>(e: ExprText<T>): ViewText<T>;
/** 数值表达式上 SQLite 专有的方法。 */
export function sqlite<T>(e: ExprNumber<T> | ExprBigint<T> | ExprDecimal<T>): ViewNumber<T>;
export function sqlite<T>(e: Expr<T>): ViewText<T> | ViewNumber<T> {
  return e.type.kind === "text" ? new ViewText(e) : new ViewNumber(e);
}

/** 文本表达式上 SQLite 专有的方法，由 {@link sqlite} 得到。 */
export class ViewText<T> {
  constructor(private readonly e: Expr<T>) {}

  /**
   * 区分大小写的通配匹配 `e GLOB pattern`：`*` 任意串、`?` 一个字符、`[...]` 字符集。SQLite 的 LIKE 不区分 ASCII 大小写，
   * 要区分时用它。写进当前的条件块。
   */
  glob(pattern: string): void {
    const e = this.e;
    const p = param(text, pattern);
    addCondition(
      condition((w) => {
        requireSqlite(w, "GLOB");
        w.render(e).sql(" GLOB ").render(p);
      }),
    );
  }
}

/** 数值表达式上 SQLite 专有的方法，由 {@link sqlite} 得到。 */
export class ViewNumber<T> {
  constructor(private readonly e: Expr<T>) {}

  /** 中位数 `median(e)`（需要 SQLITE_ENABLE_PERCENTILE，3.51 起）。 */
  median(): ExprNumber<number | null> {
    return call<number | null>(MEDIAN, double, this.e.cast(CastType.double()));
  }
}

/** 连续百分位数 `percentile_cont(e, p)`，p 取 0 到 1，是普通聚合（不是 WITHIN GROUP）；需要 SQLITE_ENABLE_PERCENTILE。 */
export function percentileCont(p: number, e: Expr<NumericValue | null>): ExprNumber<number | null> {
  return call<number | null>(PERCENTILE_CONT, double, e.cast(CastType.double()), lit(fraction(p)));
}

/** 离散百分位数 `percentile_disc(e, p)`：取实际存在的一个值；规则同 {@link percentileCont}。 */
export function percentileDisc<T>(p: number, e: Expr<T>): ExprOf<T | null> {
  return call<T | null>(PERCENTILE_DISC, e.type as SqlType<NonNullable<T | null>>, e, lit(fraction(p)));
}
