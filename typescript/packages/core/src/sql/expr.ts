import { FunctionDef } from "./dialect.js";
import { COLUMN_TYPES, SUBQUERY } from "../internal.js";
import type { From, Query } from "../query/query.js";
import { typeOf, type CastType, type SqlType } from "../schema/types.js";
import type { Renderable, SqlWriter } from "./writer.js";

declare const valueType: unique symbol;

/** `U`；当 `T` 可为 null 时是 `U | null`：函数保持操作数的可空性。 */
export type KeepNull<T, U> = null extends T ? U | null : U;

/**
 * 产生 TypeScript 类型 T 的值的 SQL 表达式（值可能缺失时 T 含 null）。
 * 要求操作数同为 T 的运算写成实例方法：先由接收者确定 T 再检查参数，
 * 所以文本列不会与数字比较。参数写成重载（值，或表达式），而不是 `value | Expr` 联合，
 * 后者会削弱类型推断。
 *
 * 继承这个类（或用 {@link expression}）即可增加表达式节点。
 */
export abstract class Expr<T> implements Renderable {
  declare readonly [valueType]: T;

  constructor(readonly type: SqlType<NonNullable<T>>) {}

  /** 把表达式写入 SQL 写入器。 */
  abstract render(w: SqlWriter): void;

  /** 单独选取该表达式时使用的列标签；列的标签就是属性名。 */
  get label(): string | undefined {
    return undefined;
  }

  // ---------- 比较 ----------

  /** 等于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  eq(value: NonNullable<T> | null | undefined): Cond;
  eq(other: Expr<NonNullable<T> | null>): Cond;
  eq(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Cond {
    if (!(arg instanceof Expr) && skips(arg)) return Cond.EMPTY;
    return compare(this, "=", this.#operand(arg));
  }

  /** 不等于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  ne(value: NonNullable<T> | null | undefined): Cond;
  ne(other: Expr<NonNullable<T> | null>): Cond;
  ne(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Cond {
    if (!(arg instanceof Expr) && skips(arg)) return Cond.EMPTY;
    return compare(this, "<>", this.#operand(arg));
  }

  /** 大于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  gt(value: NonNullable<T> | null | undefined): Cond;
  gt(other: Expr<NonNullable<T> | null>): Cond;
  gt(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Cond {
    if (!(arg instanceof Expr) && skips(arg)) return Cond.EMPTY;
    return compare(this, ">", this.#operand(arg));
  }

  /** 大于等于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  ge(value: NonNullable<T> | null | undefined): Cond;
  ge(other: Expr<NonNullable<T> | null>): Cond;
  ge(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Cond {
    if (!(arg instanceof Expr) && skips(arg)) return Cond.EMPTY;
    return compare(this, ">=", this.#operand(arg));
  }

  /** 小于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  lt(value: NonNullable<T> | null | undefined): Cond;
  lt(other: Expr<NonNullable<T> | null>): Cond;
  lt(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Cond {
    if (!(arg instanceof Expr) && skips(arg)) return Cond.EMPTY;
    return compare(this, "<", this.#operand(arg));
  }

  /** 小于等于：参数是值或另一个表达式；skipEmpty 下值为空时没有条件。 */
  le(value: NonNullable<T> | null | undefined): Cond;
  le(other: Expr<NonNullable<T> | null>): Cond;
  le(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Cond {
    if (!(arg instanceof Expr) && skips(arg)) return Cond.EMPTY;
    return compare(this, "<=", this.#operand(arg));
  }

  /** BETWEEN low AND high；skipEmpty 下缺一边时只留下另一边，两边都缺时没有条件。 */
  between(low: NonNullable<T> | null | undefined, high: NonNullable<T> | null | undefined): Cond;
  between(low: Expr<NonNullable<T> | null>, high: Expr<NonNullable<T> | null>): Cond;
  between(
    low: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined,
    high: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined,
  ): Cond {
    if (!(low instanceof Expr) && !(high instanceof Expr) && (skips(low) || skips(high))) {
      return all(this.ge(low as NonNullable<T> | null | undefined), this.le(high as NonNullable<T> | null | undefined));
    }
    const [lo, hi] = [this.#operand(low), this.#operand(high)];
    return condition((w) => w.render(this).sql(" BETWEEN ").render(lo).sql(" AND ").render(hi));
  }

  /**
   * 半开区间 [from, until)：写成 `x >= from AND x < until`，适合日期和时间段，
   * 例如 `l.createdOn.range(monthStart, nextMonthStart)`。skipEmpty 下缺少的一端被忽略。
   */
  range(from: NonNullable<T> | null | undefined, until: NonNullable<T> | null | undefined): Cond {
    return all(this.ge(from), this.lt(until));
  }

  /** IN 列表或子查询；空列表恒假（skipEmpty 下没有条件），很长的列表按方言上限拆开。 */
  in(values: readonly NonNullable<T>[] | null | undefined): Cond;
  in(subquery: Query<NonNullable<T> | null>): Cond;
  in(arg: readonly NonNullable<T>[] | Query<NonNullable<T> | null> | null | undefined): Cond {
    if (!Array.isArray(arg) && !(arg instanceof Object)) return skipsOrThrow(arg);
    if (Array.isArray(arg) && skips(arg)) return Cond.EMPTY;
    return Array.isArray(arg) ? inList(this, arg.map((v) => this.#param(v)), false) : inQuery(this, arg as Query<unknown>, false);
  }

  /** NOT IN 列表或子查询；空列表恒真（skipEmpty 下没有条件）。 */
  notIn(values: readonly NonNullable<T>[] | null | undefined): Cond;
  notIn(subquery: Query<NonNullable<T> | null>): Cond;
  notIn(arg: readonly NonNullable<T>[] | Query<NonNullable<T> | null> | null | undefined): Cond {
    if (!Array.isArray(arg) && !(arg instanceof Object)) return skipsOrThrow(arg);
    if (Array.isArray(arg) && skips(arg)) return Cond.EMPTY;
    return Array.isArray(arg) ? inList(this, arg.map((v) => this.#param(v)), true) : inQuery(this, arg as Query<unknown>, true);
  }

  /** IS NULL。 */
  isNull(): Cond {
    return condition((w) => w.render(this).sql(" IS NULL"));
  }

  /** IS NOT NULL。 */
  isNotNull(): Cond {
    return condition((w) => w.render(this).sql(" IS NOT NULL"));
  }

  // ---------- 同类型函数 ----------

  /** 取自身和各备选值中第一个非空的；只有最后一个备选值恒不为空，结果才是非空类型。 */
  coalesce(fallback: NonNullable<T>): Expr<NonNullable<T>>;
  coalesce(fallback: Expr<NonNullable<T>>): Expr<NonNullable<T>>;
  coalesce(...fallbacks: [...Expr<NonNullable<T> | null>[], NonNullable<T> | Expr<NonNullable<T>>]): Expr<NonNullable<T>>;
  coalesce(...fallbacks: Expr<NonNullable<T> | null>[]): Expr<NonNullable<T> | null>;
  coalesce(...fallbacks: (NonNullable<T> | Expr<NonNullable<T> | null>)[]): Expr<NonNullable<T> | null> {
    if (fallbacks.length === 0) throw new TypeError("coalesce() needs at least one fallback");
    return functionCall<NonNullable<T> | null>(this.type, "COALESCE", [this, ...fallbacks.map((f) => this.#operand(f))]);
  }

  /** NULLIF(自身, value)：两者相等时得到 NULL。 */
  nullif(value: NonNullable<T>): Expr<NonNullable<T> | null> {
    return functionCall<NonNullable<T> | null>(this.type, "NULLIF", [this, this.#param(value)]);
  }

  // ---------- 命名、排序、转换 ----------

  /** 给表达式起列标签，投影时作为结果的键。 */
  as(label: string): Expr<T> {
    return new Labeled(this, label);
  }

  /** 升序排序项。 */
  asc(): Order {
    return new Order(this, false, undefined);
  }

  /** 降序排序项。 */
  desc(): Order {
    return new Order(this, true, undefined);
  }

  /** CAST 成目标类型；可空性保持不变。 */
  cast<U>(target: CastType<U>): Expr<KeepNull<T, U>> {
    return new CastExpr(this, target as CastType<unknown>) as unknown as Expr<KeepNull<T, U>>;
  }

  /** 把值按本表达式的类型绑定成参数。 */
  #param(value: NonNullable<T>): Expr<NonNullable<T>> {
    return param(this.type, value);
  }

  /** 表达式原样使用，值则绑定成参数。 */
  #operand(arg: NonNullable<T> | Expr<NonNullable<T> | null> | null | undefined): Expr<unknown> {
    return arg instanceof Expr ? arg : this.#param(arg as NonNullable<T>);
  }
}

// ---------- skipEmpty ----------

/** 当前是否处于 skipEmpty 模式。 */
let skipping = false;

/**
 * 在 skipEmpty 模式下运行 body：其中与 null、undefined、空白字符串或空数组比较的条件直接省略。
 * 回调都是同步执行的，所以用模块级的开关即可；嵌套的子查询沿用外层的模式。
 */
export function withSkipEmpty<R>(on: boolean, body: () => R): R {
  if (!on || skipping) return body();
  skipping = true;
  try {
    return body();
  } finally {
    skipping = false;
  }
}

/** skipEmpty 模式下，这个值上的条件是否应当省略。 */
export function skips(value: unknown): boolean {
  return skipping && (value == null || (typeof value === "string" && value.trim() === "") || (Array.isArray(value) && value.length === 0));
}

/** IN 的参数是 null 或 undefined：skipEmpty 下没有条件，否则报错。 */
function skipsOrThrow(value: unknown): Cond {
  if (skips(value)) return Cond.EMPTY;
  throw new TypeError("IN needs a list or a subquery; use skipEmpty() on the query to drop conditions on empty values");
}

/** 绑定参数，按表达式的类型绑定，挂载的转换器作用于这个值。 */
export function param<T>(type: SqlType<T>, value: T): Expr<T> {
  if (value == null) throw new TypeError("Comparing with NULL never matches; use isNull(), or skipEmpty() on the query to drop conditions on empty values");
  return new Param(type as SqlType<NonNullable<T>>, value);
}

/** 用一个函数写出的表达式节点；方言包增加自己语法的方式。 */
export function expression<T>(type: SqlType<NonNullable<T>>, render: (w: SqlWriter) => void): Expr<T> {
  return new (class extends Expr<T> {
    render(w: SqlWriter): void {
      render(w);
    }
  })(type);
}

// ---------- 条件 ----------

/**
 * 条件。与 `Expr<boolean>` 分开，因为有些数据库（HANA）不能直接选取谓词。
 * {@link Cond.EMPTY} 表示“没有条件”，组合时会消失。
 */
export abstract class Cond implements Renderable {
  /** “没有条件”：参与 and / or / not 时被丢弃，不能直接渲染。 */
  static readonly EMPTY: Cond = new (class extends Cond {
    render(): void {
      throw new Error("Cond.EMPTY is dropped before rendering");
    }

    override toString(): string {
      return "EMPTY";
    }
  })();

  /** 把条件写入 SQL 写入器。 */
  abstract render(w: SqlWriter): void;

  /** 与另一个条件 AND；任一方为空条件时只留下另一方。 */
  and(other: Cond): Cond {
    return all(this, other);
  }

  /** 与另一个条件 OR；任一方为空条件时只留下另一方。 */
  or(other: Cond): Cond {
    return any(this, other);
  }

  /** 取反；空条件取反仍是空条件。 */
  not(): Cond {
    return not(this);
  }

  /** 是否为“没有条件”。 */
  get isEmpty(): boolean {
    return this === Cond.EMPTY;
  }
}

/** 用一个函数写出的条件节点；方言包增加自己谓词的方式。 */
export function condition(render: (w: SqlWriter) => void): Cond {
  return new (class extends Cond {
    render(w: SqlWriter): void {
      render(w);
    }
  })();
}

/** 去掉空条件；条件本身为 null 或 undefined 是调用错误。 */
function kept(conds: readonly Cond[]): Cond[] {
  return conds.filter((c) => {
    if (c == null) throw new TypeError("A condition is null or undefined; use Cond.EMPTY for no condition");
    return !c.isEmpty;
  });
}

/** 用 AND 连接全部条件，空条件被丢弃；一个都不剩时返回空条件。 */
export function all(...conds: readonly Cond[]): Cond {
  const list = kept(conds);
  if (list.length === 0) return Cond.EMPTY;
  if (list.length === 1) return list[0]!;
  return new Conjunction(list.flatMap(conjuncts));
}

/** 用 AND 连接的多个条件；保留各项，供一对多关系的 `first(...)` 拆开连接条件。 */
export class Conjunction extends Cond {
  constructor(readonly parts: readonly Cond[]) {
    super();
  }

  /** 写出 `a AND b AND ...`。 */
  render(w: SqlWriter): void {
    w.join(this.parts, " AND ");
  }
}

/** 把条件拆成用 AND 连接的各项；不是 AND 的条件就是它自己。 */
export function conjuncts(c: Cond): readonly Cond[] {
  if (c.isEmpty) return [];
  return c instanceof Conjunction ? c.parts : [c];
}

/** 用 OR 连接全部条件，空条件被丢弃；每个操作数都加括号，使 OR 里的 AND 保持原意。 */
export function any(...conds: readonly Cond[]): Cond {
  const list = kept(conds);
  if (list.length === 0) return Cond.EMPTY;
  if (list.length === 1) return list[0]!;
  return condition((w) => {
    w.sql("(");
    list.forEach((c, i) => w.sql(i > 0 ? " OR (" : "(").render(c).sql(")"));
    w.sql(")");
  });
}

/** 条件取反：NOT (c)。 */
export function not(c: Cond): Cond {
  return c.isEmpty ? Cond.EMPTY : condition((w) => w.sql("NOT (").render(c).sql(")"));
}

/** 恒假与恒真的条件，供空 IN 列表使用。 */
const FALSE = condition((w) => w.sql("1 = 0"));
const TRUE = condition((w) => w.sql("1 = 1"));

/** 用比较运算符连接两个操作数：`left operator right`。 */
export function compare(left: Renderable, operator: string, right: Renderable): Cond {
  return new Comparison(left, operator, right);
}

/** 比较条件 `left op right`；保留两侧，一对多关系的 `first(...)` 从中找出连接两张表的等值键。 */
export class Comparison extends Cond {
  constructor(
    readonly left: Renderable,
    readonly operator: string,
    readonly right: Renderable,
  ) {
    super();
  }

  /** 写出 `left op right`。 */
  render(w: SqlWriter): void {
    w.render(this.left).sql(` ${this.operator} `).render(this.right);
  }
}

/** IN / NOT IN 值列表；超过方言上限时拆成多个谓词，IN 用 OR 连接，NOT IN 用 AND 连接。 */
function inList(e: Expr<unknown>, values: readonly Expr<unknown>[], negate: boolean): Cond {
  if (values.length === 0) return negate ? TRUE : FALSE;
  const keyword = negate ? " NOT IN (" : " IN (";
  return condition((w) => {
    const max = Math.max(1, w.dialect.maxInListSize);
    if (values.length <= max) {
      w.render(e).sql(keyword).join(values, ", ").sql(")");
      return;
    }
    w.sql("(");
    for (let from = 0; from < values.length; from += max) {
      if (from > 0) w.sql(negate ? " AND " : " OR ");
      w.render(e).sql(keyword).join(values.slice(from, from + max), ", ").sql(")");
    }
    w.sql(")");
  });
}

/** IN / NOT IN 子查询。 */
function inQuery(e: Expr<unknown>, query: Query<unknown>, negate: boolean): Cond {
  return condition((w) => {
    w.render(e).sql(negate ? " NOT IN (" : " IN (");
    query[SUBQUERY](w);
    w.sql(")");
  });
}

/** 对查询、或对没有 SELECT 列表的作用域做 EXISTS：`exists(from(Vbkd).where((v) => ...))`。 */
export function exists(subquery: Query<unknown> | From<readonly unknown[]>): Cond {
  return condition((w) => {
    w.sql("EXISTS (");
    subquery[SUBQUERY](w);
    w.sql(")");
  });
}

/** NOT EXISTS，参数同 {@link exists}。 */
export function notExists(subquery: Query<unknown> | From<readonly unknown[]>): Cond {
  return condition((w) => {
    w.sql("NOT EXISTS (");
    subquery[SUBQUERY](w);
    w.sql(")");
  });
}

// ---------- 排序 ----------

/** ORDER BY 的一项。 */
export class Order implements Renderable {
  constructor(
    readonly expr: Expr<unknown>,
    readonly descending: boolean,
    readonly nullsPlacement: boolean | undefined,
  ) {}

  /** NULL 排在前面。 */
  nullsFirst(): Order {
    return new Order(this.expr, this.descending, true);
  }

  /** NULL 排在后面。 */
  nullsLast(): Order {
    return new Order(this.expr, this.descending, false);
  }

  /** 由方言写出这一项，因为 NULL 位置的写法因库而异。 */
  render(w: SqlWriter): void {
    w.dialect.orderItem(w, this.expr, this.descending, this.nullsPlacement);
  }
}

/** 用户写的排序项：Order，或表示升序的表达式。 */
export type OrderItem = Order | Expr<unknown>;

/** 把排序项规整成 Order；表达式视为升序。 */
export function toOrder(item: OrderItem): Order {
  return item instanceof Order ? item : item.asc();
}

// ---------- 表达式节点 ----------

/** 绑定参数；写入器内联取值且方言有对应字面量时写成字面量。 */
class Param<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly value: T,
  ) {
    super(type);
  }

  /** 绑定参数，或在内联时写成字面量。 */
  render(w: SqlWriter): void {
    const type = this.type as SqlType<unknown>;
    if (w.inlining && w.dialect.hasLiteral(this.value, type)) w.literal(this.value, type);
    else w.bind(this.value, type);
  }
}

/** 直接写进 SQL 的字面量，不绑定参数。 */
export class Literal<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly value: T,
  ) {
    super(type);
  }

  /** 写出字面量。 */
  render(w: SqlWriter): void {
    w.literal(this.value, this.type as SqlType<unknown>);
  }
}

/** 写出某个数据源出现位置上的列，使用语句为它分配的别名。 */
export interface ColumnOwner {
  renderColumn(w: SqlWriter, column: SourceColumn): void;
}

/** 数据源提供的一列：属性名和类型。 */
export interface SourceColumn {
  readonly name: string;
  readonly type: SqlType<unknown>;
}

/** 某个数据源的列引用。 */
export class Column<T> extends Expr<T> {
  constructor(
    readonly owner: ColumnOwner,
    readonly column: SourceColumn,
  ) {
    super(column.type as SqlType<NonNullable<T>>);
  }

  /** 由所属数据源写出带别名的列。 */
  render(w: SqlWriter): void {
    this.owner.renderColumn(w, this.column);
  }

  /** 列标签是属性名。 */
  override get label(): string {
    return this.column.name;
  }
}

/** 带列标签的包装，渲染时与内部表达式相同。 */
class Labeled<T> extends Expr<T> {
  readonly #label: string;

  constructor(
    readonly inner: Expr<T>,
    label: string,
  ) {
    super(inner.type);
    if (label.trim() === "") throw new TypeError("A label must not be blank");
    this.#label = label;
  }

  /** 与内部表达式相同，标签只影响投影。 */
  render(w: SqlWriter): void {
    this.inner.render(w);
  }

  /** 显式指定的标签。 */
  override get label(): string {
    return this.#label;
  }
}

/** 函数调用：{@link FunctionDef} 按方言渲染，字符串名则直接写成 NAME(args)。 */
class Call<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly fn: FunctionDef | string,
    readonly args: readonly Renderable[],
  ) {
    super(type);
  }

  /** 按方言渲染函数定义，或直接写成 NAME(args)。 */
  render(w: SqlWriter): void {
    if (typeof this.fn === "string") FunctionDef.call(w, this.fn, this.args);
    else this.fn.render(w, this.args);
  }
}

/** 构造函数调用表达式，`type` 是结果类型。 */
export function functionCall<T>(type: SqlType<NonNullable<T>>, fn: FunctionDef | string, args: readonly Renderable[]): Expr<T> {
  return new Call(type, fn, args);
}

/** 原生 SQL 模板的参数：节点原位渲染，其他值绑定（内联时写成字面量），数组展开成逗号分隔的多个参数。 */
export type TemplateArg = Renderable | TemplateValue | readonly TemplateValue[];

/** 原生 SQL 模板里绑定的值。 */
export type TemplateValue = string | number | bigint | boolean | Uint8Array;

/** 渲染 SQL 模板：交错写出文本片段和参数。 */
export function renderTemplate(w: SqlWriter, parts: readonly string[], args: readonly TemplateArg[]): void {
  parts.forEach((part, i) => {
    w.sql(part);
    if (i < args.length) {
      const arg = args[i];
      if (arg == null) throw new TypeError(`Template argument ${i} is ${arg}; write NULL in the template instead`);
      if (Array.isArray(arg)) {
        if (arg.length === 0) throw new TypeError(`Template argument ${i} is an empty array`);
        (arg as readonly TemplateValue[]).forEach((v, j) => {
          if (j > 0) w.sql(", ");
          w.render(param(typeOf(v), v));
        });
      } else if (typeof arg === "object" && !(arg instanceof Uint8Array)) w.render(arg as Renderable);
      else w.render(param(typeOf(arg), arg as TemplateValue));
    }
  });
}

/** 算术表达式，整体加括号：`(a + b + ...)`。 */
export class Arithmetic<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly operator: string,
    readonly operands: readonly Expr<unknown>[],
  ) {
    super(type);
  }

  /** 写出带括号、用运算符连接的操作数。 */
  render(w: SqlWriter): void {
    w.sql("(").join(this.operands, ` ${this.operator} `).sql(")");
  }
}

/** CAST(inner AS 目标类型)，类型名由方言决定。 */
class CastExpr extends Expr<unknown> {
  constructor(
    readonly inner: Expr<unknown>,
    readonly target: CastType<unknown>,
  ) {
    super(target.type as SqlType<{}>);
  }

  /** 写出 CAST(内部表达式 AS 目标类型)。 */
  render(w: SqlWriter): void {
    w.sql("CAST(").render(this.inner).sql(" AS ").sql(w.dialect.castType(this.target)).sql(")");
  }
}

/** 标量子查询：必须恰好选取一列，结果可能为 NULL。 */
export class Scalar<T> extends Expr<T | null> {
  constructor(readonly query: Query<T>) {
    const types = query[COLUMN_TYPES];
    if (types.length !== 1) throw new Error(`A scalar subquery must select exactly one column, this one selects ${types.length}`);
    super(types[0] as SqlType<NonNullable<T>>);
  }

  /** 把子查询写在括号里。 */
  render(w: SqlWriter): void {
    w.sql("(");
    this.query[SUBQUERY](w);
    w.sql(")");
  }
}

/** 固定的 SQL 文本，例如 NULL 或 CURRENT_DATE。 */
export function raw<T>(type: SqlType<NonNullable<T>>, sql: string): Expr<T> {
  return expression(type, (w) => w.sql(sql));
}

// ---------- 窗口、聚合、CASE ----------

/** ROWS 窗口框架的边界。 */
export class Bound {
  private constructor(readonly sql: string) {}

  /** UNBOUNDED PRECEDING：从分区开头算起。 */
  static unboundedPreceding(): Bound {
    return new Bound("UNBOUNDED PRECEDING");
  }

  /** 当前行之前的 `rows` 行。 */
  static preceding(rows: number): Bound {
    return new Bound(`${Bound.#rows(rows)} PRECEDING`);
  }

  /** CURRENT ROW：当前行。 */
  static currentRow(): Bound {
    return new Bound("CURRENT ROW");
  }

  /** 当前行之后的 `rows` 行。 */
  static following(rows: number): Bound {
    return new Bound(`${Bound.#rows(rows)} FOLLOWING`);
  }

  /** UNBOUNDED FOLLOWING：到分区末尾为止。 */
  static unboundedFollowing(): Bound {
    return new Bound("UNBOUNDED FOLLOWING");
  }

  /** 校验框架偏移是非负整数。 */
  static #rows(rows: number): number {
    if (!Number.isInteger(rows) || rows < 0) throw new RangeError("Frame offsets must be non-negative integers");
    return rows;
  }
}

/** OVER (...) 的内容，在 `over(w => w.partitionBy(...).orderBy(...))` 里构建。 */
export class Window implements Renderable {
  static readonly EMPTY = new Window([], [], undefined);

  private constructor(
    readonly partition: readonly Expr<unknown>[],
    readonly order: readonly Order[],
    readonly frame: string | undefined,
  ) {}

  /** PARTITION BY 分区键。 */
  partitionBy(...keys: Expr<unknown>[]): Window {
    return new Window(keys, this.order, this.frame);
  }

  /** 窗口内的 ORDER BY。 */
  orderBy(...items: OrderItem[]): Window {
    return new Window(this.partition, items.map(toOrder), this.frame);
  }

  /** ROWS BETWEEN start AND end；RANGE 框架各库不同，放在方言包里。 */
  rows(start: Bound, end: Bound): Window {
    return new Window(this.partition, this.order, `ROWS BETWEEN ${start.sql} AND ${end.sql}`);
  }

  /** 依次写出 PARTITION BY、ORDER BY 和框架，只写出存在的部分。 */
  render(w: SqlWriter): void {
    let sep = "";
    if (this.partition.length > 0) {
      w.sql("PARTITION BY ").join(this.partition, ", ");
      sep = " ";
    }
    if (this.order.length > 0) {
      w.sql(sep + "ORDER BY ").join(this.order, ", ");
      sep = " ";
    }
    if (this.frame !== undefined) w.sql(sep + this.frame);
  }
}

/** 带窗口的函数：`fn OVER (window)`。 */
/** node 是否是加了 OVER (...) 的窗口函数；QUALIFY 用它找出条件里的窗口函数。 */
export function isWindow(node: Renderable): boolean {
  return node instanceof Over;
}

class Over<T> extends Expr<T> {
  constructor(
    readonly fn: Expr<T>,
    readonly window: Window,
  ) {
    super(fn.type);
  }

  /** 写出 `fn OVER (window)`。 */
  render(w: SqlWriter): void {
    w.render(this.fn).sql(" OVER (").render(this.window).sql(")");
  }
}

/** 聚合函数，例如 SUM(x)；可以直接使用，也可以通过 {@link over} 作为窗口函数。 */
export class Aggregate<T> extends Expr<T> {
  constructor(
    type: SqlType<NonNullable<T>>,
    readonly name: string,
    readonly distinct: boolean,
    readonly args: readonly Expr<unknown>[],
    /** 只聚合满足它的行；没有时不过滤。 */
    readonly condition?: Cond,
  ) {
    super(type);
  }

  /**
   * 条件聚合：只聚合满足 cond 的行，例如 `sum(l.quantity).filter(l.unit.eq("KG"))`。
   * 三个库都写成 `SUM(CASE WHEN 条件 THEN x END)`，COUNT(*) 写成 `COUNT(CASE WHEN 条件 THEN 1 END)`；
   * 多次调用按 AND 合并，空条件（例如 skipEmpty 省略掉的）不过滤。
   */
  filter(cond: Cond): Aggregate<T> {
    if (cond.isEmpty) return this;
    return new Aggregate(this.type, this.name, this.distinct, this.args, this.condition ? all(this.condition, cond) : cond);
  }

  /** 把聚合作为窗口函数使用。 */
  over(window: (w: Window) => Window): Expr<T> {
    return new Over(this, window(Window.EMPTY));
  }

  /** 写出 NAME([DISTINCT ]args)；没有参数时是 NAME(*)，有过滤条件时参数换成 CASE WHEN 条件 THEN 参数 END。 */
  render(w: SqlWriter): void {
    w.sql(this.name + "(");
    if (this.distinct) w.sql("DISTINCT ");
    if (this.condition) {
      w.sql("CASE WHEN ").render(this.condition).sql(" THEN ");
      if (this.args.length === 0) w.sql("1");
      else w.join(this.args, ", ");
      w.sql(" END");
    } else if (this.args.length === 0) w.sql("*");
    else w.join(this.args, ", ");
    w.sql(")");
  }
}

/** ROW_NUMBER、RANK、LAG 等：在 {@link over} 给出窗口之前还不是表达式。 */
export class WindowFunction<T> {
  constructor(
    readonly type: SqlType<NonNullable<T>>,
    readonly name: string,
    readonly args: readonly Renderable[],
  ) {}

  /** 指定窗口，得到可选取的表达式。 */
  over(window: (w: Window) => Window): Expr<T> {
    return new Over(functionCall<T>(this.type, this.name, this.args), window(Window.EMPTY));
  }
}

/** CASE WHEN ... THEN ... 的构建器；第一个分支决定其余分支的类型 T。 */
export class Case<T> {
  constructor(
    readonly type: SqlType<NonNullable<T>>,
    readonly branches: readonly (readonly [Cond, Expr<unknown>])[],
  ) {}

  /** 增加一个分支：条件成立时取 `result`。 */
  when(condition: Cond, result: NonNullable<T>): Case<T>;
  when(condition: Cond, result: Expr<NonNullable<T>>): Case<T>;
  when(condition: Cond, result: Expr<NonNullable<T> | null>): Case<T | null>;
  when(condition: Cond, result: NonNullable<T> | Expr<NonNullable<T> | null>): Case<T | null> {
    return new Case<T | null>(this.type, [...this.branches, [condition, this.#operand(result)]]);
  }

  /** ELSE 分支，并结束 CASE。 */
  otherwise(result: NonNullable<T>): Expr<T>;
  otherwise(result: Expr<NonNullable<T>>): Expr<T>;
  otherwise(result: Expr<NonNullable<T> | null>): Expr<T | null>;
  otherwise(result: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<T | null> {
    return this.#build(this.#operand(result));
  }

  /** 不写 ELSE：未匹配的行得到 NULL。 */
  end(): Expr<T | null> {
    return this.#build(undefined);
  }

  /** 表达式原样使用，值则绑定成参数。 */
  #operand(result: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<unknown> {
    return result instanceof Expr ? result : param(this.type, result);
  }

  /** 组装成 CASE 表达式；`otherwise` 缺省时不写 ELSE。 */
  #build(otherwise: Expr<unknown> | undefined): Expr<T | null> {
    return caseExpression<T | null>(this.type, undefined, this.branches, otherwise);
  }
}

/** 简单 CASE 的比较对象，尚无分支；见 `caseOf`。 */
export class CaseOf<V> {
  constructor(readonly subject: Expr<V>) {}

  /** 第一个分支：`subject = value` 时取 `result`，由它决定结果类型。 */
  when(value: NonNullable<V>, result: string): SimpleCase<V, string>;
  when(value: NonNullable<V>, result: number): SimpleCase<V, number>;
  when(value: NonNullable<V>, result: boolean): SimpleCase<V, boolean>;
  when<T>(value: NonNullable<V>, result: Expr<T>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: unknown): SimpleCase<V, unknown> {
    const e = result instanceof Expr ? (result as Expr<unknown>) : param(typeOf(result), result);
    return new SimpleCase<V, unknown>(this.subject, e.type as SqlType<{}>, [[param(this.subject.type, value), e]]);
  }
}

/** `CASE subject WHEN value THEN result ... END`，直接写在查询里的代码表；第一个分支决定类型 T。 */
export class SimpleCase<V, T> {
  constructor(
    readonly subject: Expr<V>,
    readonly type: SqlType<NonNullable<T>>,
    readonly branches: readonly (readonly [Expr<unknown>, Expr<unknown>])[],
  ) {}

  /** 增加一个分支：`subject = value` 时取 `result`。 */
  when(value: NonNullable<V>, result: NonNullable<T>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: Expr<NonNullable<T>>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: Expr<NonNullable<T> | null>): SimpleCase<V, T | null>;
  when(value: NonNullable<V>, result: NonNullable<T> | Expr<NonNullable<T> | null>): SimpleCase<V, T | null> {
    const e = result instanceof Expr ? result : param(this.type, result);
    return new SimpleCase<V, T | null>(this.subject, this.type, [...this.branches, [param(this.subject.type, value), e]]);
  }

  /** ELSE 分支，并结束 CASE。 */
  otherwise(result: NonNullable<T>): Expr<T>;
  otherwise(result: Expr<NonNullable<T>>): Expr<T>;
  otherwise(result: Expr<NonNullable<T> | null>): Expr<T | null>;
  otherwise(result: NonNullable<T> | Expr<NonNullable<T> | null>): Expr<T | null> {
    return caseExpression<T | null>(this.type, this.subject, this.branches, result instanceof Expr ? result : param(this.type, result));
  }

  /** 不写 ELSE：未匹配的行得到 NULL。 */
  end(): Expr<T | null> {
    return caseExpression<T | null>(this.type, this.subject, this.branches, undefined);
  }
}

/** `CASE [subject] WHEN key THEN result ... [ELSE otherwise] END`；没有 subject 时键是条件，有 subject 时键是值。 */
function caseExpression<T>(
  type: SqlType<NonNullable<T>>,
  subject: Expr<unknown> | undefined,
  branches: readonly (readonly [Renderable, Expr<unknown>])[],
  otherwise: Expr<unknown> | undefined,
): Expr<T> {
  return expression<T>(type, (w) => {
    w.sql("CASE");
    if (subject !== undefined) w.sql(" ").render(subject);
    for (const [key, result] of branches) {
      if (key instanceof Cond && key.isEmpty) throw new Error("A CASE branch has no condition");
      w.sql(" WHEN ").render(key).sql(" THEN ").render(result);
    }
    if (otherwise !== undefined) w.sql(" ELSE ").render(otherwise);
    w.sql(" END");
  });
}
