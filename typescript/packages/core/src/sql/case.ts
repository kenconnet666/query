import { collect, Cond } from "./cond.js";
import { ExprNode, expression, param, type Expr } from "./expr.js";
import { typeOf, type SqlType } from "../schema/types.js";
import type { ExprOf } from "./typed.js";
import type { Renderable } from "./writer.js";

/**
 * 开始 CASE WHEN 条件 THEN result；第一个分支决定结果的类型，后续用 `.when(...)` 追加：
 * `caseWhen(() => l.quantity.gt(new Decimal(100)), "big").when(() => l.quantity.gt(new Decimal(10)), "medium").otherwise("small")`。
 * 条件写在块里，块里的多个条件是 AND。
 */
export function caseWhen<T>(condition: () => void, result: Expr<T>): Case<T>;
export function caseWhen(condition: () => void, result: string): Case<string>;
export function caseWhen(condition: () => void, result: number): Case<number>;
export function caseWhen(condition: () => void, result: boolean): Case<boolean>;
export function caseWhen<T>(condition: () => void, result: NonNullable<T>): Case<T>;
export function caseWhen(condition: () => void, result: unknown): Case<unknown> {
  const e = operand(undefined, result);
  return new Case(e.type as SqlType<{}>, [[branch(condition), e]]);
}

/** `CASE subject WHEN value THEN result ... END`：`caseOf(code).when("1", "own").when("2", "supplier").otherwise("other")`。 */
export function caseOf<V>(subject: Expr<V>): CaseOf<V> {
  return new CaseOf(subject);
}

/** CASE WHEN ... THEN ... 的构建器；第一个分支决定其余分支的类型 T。 */
export class Case<T> {
  constructor(
    readonly type: SqlType<NonNullable<T>>,
    readonly branches: readonly (readonly [Cond, Expr<unknown>])[],
  ) {}

  /** 增加一个分支：块里的条件成立时取 `result`。 */
  when(condition: () => void, result: NonNullable<T>): Case<T>;
  when(condition: () => void, result: Expr<NonNullable<T>>): Case<T>;
  when(condition: () => void, result: Expr<NonNullable<T> | null>): Case<T | null>;
  when(condition: () => void, result: unknown): unknown {
    return new Case<T | null>(this.type, [...this.branches, [branch(condition), operand(this.type, result)]]);
  }

  /** ELSE 分支，并结束 CASE。 */
  otherwise(result: NonNullable<T>): ExprOf<T>;
  otherwise(result: Expr<NonNullable<T>>): ExprOf<T>;
  otherwise(result: Expr<NonNullable<T> | null>): ExprOf<T | null>;
  otherwise(result: unknown): unknown {
    return caseExpression<T | null>(this.type, undefined, this.branches, operand(this.type, result));
  }

  /** 不写 ELSE：未匹配的行得到 NULL。 */
  end(): ExprOf<T | null> {
    return caseExpression<T | null>(this.type, undefined, this.branches, undefined);
  }
}

/** 简单 CASE 的比较对象，尚无分支；见 {@link caseOf}。 */
export class CaseOf<V> {
  constructor(readonly subject: Expr<V>) {}

  /** 第一个分支：`subject = value` 时取 `result`，由它决定结果类型。 */
  when<T>(value: NonNullable<V>, result: Expr<T>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: string): SimpleCase<V, string>;
  when(value: NonNullable<V>, result: number): SimpleCase<V, number>;
  when(value: NonNullable<V>, result: boolean): SimpleCase<V, boolean>;
  when<T>(value: NonNullable<V>, result: NonNullable<T>): SimpleCase<V, T>;
  when(value: NonNullable<V>, result: unknown): SimpleCase<V, unknown> {
    const e = operand(undefined, result);
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
  when(value: NonNullable<V>, result: unknown): unknown {
    return new SimpleCase<V, T | null>(this.subject, this.type, [...this.branches, [param(this.subject.type, value), operand(this.type, result)]]);
  }

  /** ELSE 分支，并结束 CASE。 */
  otherwise(result: NonNullable<T>): ExprOf<T>;
  otherwise(result: Expr<NonNullable<T>>): ExprOf<T>;
  otherwise(result: Expr<NonNullable<T> | null>): ExprOf<T | null>;
  otherwise(result: unknown): unknown {
    return caseExpression<T | null>(this.type, this.subject, this.branches, operand(this.type, result));
  }

  /** 不写 ELSE：未匹配的行得到 NULL。 */
  end(): ExprOf<T | null> {
    return caseExpression<T | null>(this.type, this.subject, this.branches, undefined);
  }
}

/** 分支的条件块；没有条件（例如 skipEmpty 省略掉了）是错误，否则这个分支会恒真或恒假。 */
function branch(condition: () => void): Cond {
  const c = collect(condition);
  if (c.isEmpty) throw new Error("A CASE branch has no condition");
  return c;
}

/** 分支的结果：表达式原样使用，值按 type（第一个分支时按值本身）绑定成参数。 */
function operand(type: SqlType<unknown> | undefined, result: unknown): Expr<unknown> {
  if (result instanceof ExprNode) return result as Expr<unknown>;
  return param((type ?? typeOf(result)) as SqlType<{}>, result as {});
}

/** `CASE [subject] WHEN key THEN result ... [ELSE otherwise] END`；没有 subject 时键是条件，有 subject 时键是值。 */
function caseExpression<T>(
  type: SqlType<NonNullable<T>>,
  subject: Expr<unknown> | undefined,
  branches: readonly (readonly [Renderable, Expr<unknown>])[],
  otherwise: Expr<unknown> | undefined,
): ExprOf<T> {
  return expression<T>(type, (w) => {
    w.sql("CASE");
    if (subject !== undefined) w.sql(" ").render(subject);
    for (const [key, result] of branches) w.sql(" WHEN ").render(key).sql(" THEN ").render(result);
    if (otherwise !== undefined) w.sql(" ELSE ").render(otherwise);
    w.sql(" END");
  });
}
