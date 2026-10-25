import { COLUMN_TYPES, NAMED, SUBQUERY } from "../internal.js";
import type { From, Query } from "../query/query.js";
import { Quantified, type Expr } from "./expr.js";
import type { Renderable, SqlWriter } from "./writer.js";

// expr.ts 与本模块互相引用：两边都只在函数体里用对方的值，模块顶层不用，所以先加载哪一个都可以。

/**
 * 条件。与 `Expr<boolean>` 分开，因为有些数据库（HANA）不能直接选取谓词。使用方不直接拿到它：条件方法把条件写进
 * 当前的条件块（见 {@link collect}），条件对象留给方言包和自定义的条件节点。{@link Cond.EMPTY} 表示"没有条件"，组合时会消失。
 */
export abstract class Cond implements Renderable {
  /** "没有条件"：组合时被丢弃，写进条件块时被忽略，不能直接渲染。 */
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

  /** 是否为"没有条件"。 */
  get isEmpty(): boolean {
    return this === Cond.EMPTY;
  }
}

/** 用一个函数写出的条件节点；方言包增加自己谓词的方式，得到后用 {@link addCondition} 写进条件块。 */
export function condition(render: (w: SqlWriter) => void): Cond {
  return new (class extends Cond {
    render(w: SqlWriter): void {
      render(w);
    }
  })();
}

// ---------- 条件块 ----------

/** 不收条件的回调（投影、分组、排序）在栈上压的标记。 */
const BARRIER = Object.freeze([]) as readonly Cond[];

/** 嵌套的条件块，最内层在末尾。回调都是同步执行的，所以模块级的栈就够了。 */
const blocks: (Cond[] | typeof BARRIER)[] = [];

/**
 * 语句式条件的接收处：在新的条件块里运行 block，返回块里写下的条件的 AND；没有写下条件（或都因 skipEmpty 省略）时
 * 返回 {@link Cond.EMPTY}。条件方法（`eq`、`in`、`isNull`、`exists` 等）不返回值，而是写进最内层的块：
 *
 * ```ts
 * from(Lips).where((l) => {
 *   l.delivery.contains("D");
 *   if (maker != null) l.mara.manufacturer.eq(maker);
 *   any(() => { l.unit.eq("EA"); l.unit.eq("KG"); });
 * })
 * ```
 *
 * 条件块有 where、having、qualify、连接的 on、关系的 where/any/none、`any`、`all`、`not`、`bool`、`caseWhen`、
 * 聚合的 `filter` 和全局过滤器。
 */
export function collect(block: () => void): Cond {
  return and(...conditionsOf(block));
}

/** 在新的条件块里运行 block，返回块里写下的各个条件，供 OR 这类按项组合的块使用。 */
export function conditionsOf(block: () => void): Cond[] {
  const written: Cond[] = [];
  blocks.push(written);
  try {
    block();
  } finally {
    blocks.pop();
  }
  return written;
}

/** 运行不收条件的回调（投影、分组、排序等）：其中写下的条件报错，而不是悄悄并进外层的条件块。 */
export function outside<R>(body: () => R): R {
  blocks.push(BARRIER);
  try {
    return body();
  } finally {
    blocks.pop();
  }
}

/** 把条件写进最内层的条件块；{@link Cond.EMPTY} 被丢掉。不在条件块里时报错。 */
export function addCondition(c: Cond): void {
  if (c == null) throw new TypeError("A condition is null or undefined; use Cond.EMPTY for no condition");
  const block = blocks.at(-1);
  if (block === undefined || block === BARRIER) {
    throw new Error(
      "A condition was written outside a condition block (where, having, on, any(() => ...) and the like); " +
        "to use a condition as a value, wrap it in bool(() => ...) or caseWhen(() => ..., result)",
    );
  }
  if (!c.isEmpty) (block as Cond[]).push(c);
}

/** 块里的条件全部成立：用在 {@link any} 的块里，把几个条件当作一个选项。 */
export function all(block: () => void): void {
  addCondition(collect(block));
}

/** 块里的条件任一成立（OR）：块里每写一个条件就是一个选项，`all(() => ...)` 把几个条件合成一个选项。 */
export function any(block: () => void): void {
  addCondition(or(...conditionsOf(block)));
}

/** 块里的条件不全成立：`NOT (...)`；块里没有条件时也没有条件。 */
export function not(block: () => void): void {
  addCondition(negated(collect(block)));
}

/** 对查询、或对没有 SELECT 列表的作用域做 EXISTS：`exists(from(Vbkd).where((v) => ...))`。 */
export function exists(subquery: Query<unknown> | From<readonly unknown[]>): void {
  addCondition(existence(subquery, false));
}

/** NOT EXISTS，参数同 {@link exists}。 */
export function notExists(subquery: Query<unknown> | From<readonly unknown[]>): void {
  addCondition(existence(subquery, true));
}

// ---------- 组合 ----------

/** 去掉空条件；条件本身为 null 或 undefined 是调用错误。 */
function kept(conds: readonly Cond[]): Cond[] {
  return conds.filter((c) => {
    if (c == null) throw new TypeError("A condition is null or undefined; use Cond.EMPTY for no condition");
    return !c.isEmpty;
  });
}

/** 用 AND 连接全部条件，空条件被丢弃；一个都不剩时返回空条件。 */
export function and(...conds: readonly Cond[]): Cond {
  const list = kept(conds);
  if (list.length === 0) return Cond.EMPTY;
  if (list.length === 1) return list[0]!;
  return new Conjunction(list.flatMap(conjuncts));
}

/** 用 OR 连接全部条件，空条件被丢弃；每个操作数都加括号，使 OR 里的 AND 保持原意。 */
export function or(...conds: readonly Cond[]): Cond {
  const list = kept(conds);
  if (list.length === 0) return Cond.EMPTY;
  if (list.length === 1) return list[0]!;
  return new Disjunction(list);
}

/** 条件取反：NOT (c)；空条件取反仍是空条件。 */
export function negated(c: Cond): Cond {
  return c.isEmpty ? Cond.EMPTY : condition((w) => w.sql("NOT (").render(c).sql(")"));
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

/** 用 OR 连接的多个条件；保留各项，分表按它选表。 */
export class Disjunction extends Cond {
  constructor(readonly parts: readonly Cond[]) {
    super();
  }

  /** 写出 `((a) OR (b) ...)`：每个操作数都加括号，OR 里的 AND 才保持原意。 */
  render(w: SqlWriter): void {
    w.sql("(");
    this.parts.forEach((c, i) => w.sql(i > 0 ? " OR (" : "(").render(c).sql(")"));
    w.sql(")");
  }
}

// ---------- 条件节点 ----------

/** `e BETWEEN low AND high`；保留各部分，分表按它选表。 */
export class Between extends Cond {
  constructor(
    readonly e: Renderable,
    readonly low: Renderable,
    readonly high: Renderable,
  ) {
    super();
  }

  /** 写出 `e BETWEEN low AND high`。 */
  render(w: SqlWriter): void {
    w.render(this.e).sql(" BETWEEN ").render(this.low).sql(" AND ").render(this.high);
  }
}

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

  /** 写出 `left op right`；右边是 ANY / ALL 子查询时交给方言（SQLite 改写成 EXISTS）。 */
  render(w: SqlWriter): void {
    const q = this.right;
    if (q instanceof Quantified) {
      w.dialect.quantified(w, this.left, this.operator, q.quantifier, { render: (x) => q.query[SUBQUERY](x) }, { render: (x) => q.query[NAMED](x) }, q.query.labels[0]!);
    } else w.render(this.left).sql(` ${this.operator} `).render(this.right);
  }
}

/** 恒假与恒真的条件，供空 IN 列表使用。 */
const FALSE = condition((w) => w.sql("1 = 0"));
const TRUE = condition((w) => w.sql("1 = 1"));

/** IN / NOT IN 值列表；空列表 IN 恒假、NOT IN 恒真。 */
export function inList(e: Expr<unknown>, values: readonly Expr<unknown>[], negate: boolean): Cond {
  if (values.length === 0) return negate ? TRUE : FALSE;
  return new InList(e, values, negate);
}

/** IN / NOT IN 值列表；保留各部分，分表按它选表。 */
export class InList extends Cond {
  constructor(
    readonly e: Expr<unknown>,
    readonly values: readonly Expr<unknown>[],
    readonly negate: boolean,
  ) {
    super();
  }

  /** 写出 `e IN (...)`；超过方言上限时拆成多个谓词，IN 用 OR 连接，NOT IN 用 AND 连接。 */
  render(w: SqlWriter): void {
    const keyword = this.negate ? " NOT IN (" : " IN (";
    const max = Math.max(1, w.dialect.maxInListSize);
    if (this.values.length <= max) {
      w.render(this.e).sql(keyword).join(this.values, ", ").sql(")");
      return;
    }
    w.sql("(");
    for (let from = 0; from < this.values.length; from += max) {
      if (from > 0) w.sql(this.negate ? " AND " : " OR ");
      w.render(this.e).sql(keyword).join(this.values.slice(from, from + max), ", ").sql(")");
    }
    w.sql(")");
  }
}

/** IN / NOT IN 子查询。 */
export function inQuery(e: Expr<unknown>, query: Query<unknown>, negate: boolean): Cond {
  return condition((w) => {
    w.render(e).sql(negate ? " NOT IN (" : " IN (");
    query[SUBQUERY](w);
    w.sql(")");
  });
}

/** `(a, b) IN (子查询)` 或 `NOT IN`；子查询的列数必须与键数相同。 */
export function rowInQuery(keys: readonly Expr<unknown>[], query: Query<unknown>, negate: boolean): Cond {
  if (keys.length < 2) throw new Error("A row IN needs at least two keys; use e.in(query) for one");
  const columns = query[COLUMN_TYPES].length;
  if (columns !== keys.length) throw new Error(`Row IN compares ${keys.length} keys with a subquery of ${columns} columns`);
  return condition((w) => {
    w.sql("(").join(keys, ", ").sql(negate ? ") NOT IN (" : ") IN (");
    query[SUBQUERY](w);
    w.sql(")");
  });
}

/** `EXISTS (子查询)` 或 `NOT EXISTS`。 */
export function existence(subquery: Query<unknown> | From<readonly unknown[]>, negate: boolean): Cond {
  return condition((w) => {
    w.sql(negate ? "NOT EXISTS (" : "EXISTS (");
    subquery[SUBQUERY](w);
    w.sql(")");
  });
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
