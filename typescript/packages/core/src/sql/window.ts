import type { FunctionDef } from "./dialect.js";
import { functionCall, Over, toOrder, type Expr, type Order, type OrderItem } from "./expr.js";
import type { SqlType } from "../schema/types.js";
import type { ExprOf } from "./typed.js";
import type { Renderable, SqlWriter } from "./writer.js";

// expr.ts 与本模块互相引用，只在函数体里用对方的值。

/** 窗口框架的边界。 */
export class Bound {
  private constructor(readonly sql: string) {}

  /** 方言包构造的边界，例如 `INTERVAL '7 days' PRECEDING`；只放代码里的固定文本。 */
  static custom(sql: string): Bound {
    return new Bound(sql);
  }

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

/** OVER (...) 的内容，在 `over((w) => w.partitionBy(...).orderBy(...))` 里构建。 */
export class Window implements Renderable {
  static readonly EMPTY = new Window([], [], undefined);

  private constructor(
    readonly partition: readonly Expr<unknown>[],
    readonly order: readonly Order[],
    readonly framing: Renderable | undefined,
  ) {}

  /** PARTITION BY 分区键。 */
  partitionBy(...keys: Expr<unknown>[]): Window {
    return new Window(keys, this.order, this.framing);
  }

  /** 窗口内的 ORDER BY。 */
  orderBy(...items: OrderItem[]): Window {
    return new Window(this.partition, items.map(toOrder), this.framing);
  }

  /** ROWS BETWEEN start AND end；RANGE 框架各库不同，放在方言包里，用 {@link frame} 传入。 */
  rows(start: Bound, end: Bound): Window {
    const sql = `ROWS BETWEEN ${start.sql} AND ${end.sql}`;
    return new Window(this.partition, this.order, { render: (w) => void w.sql(sql) });
  }

  /** 方言包给出的窗口框架，例如 PostgreSQL、MySQL 的 `range(...)`。 */
  frame(frame: Renderable): Window {
    return new Window(this.partition, this.order, frame);
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
    if (this.framing !== undefined) w.sql(sep).render(this.framing);
  }
}

/** ROW_NUMBER、RANK、LAG 等：在 {@link over} 给出窗口之前还不是表达式。 */
export class WindowFunction<T> {
  constructor(
    readonly type: SqlType<NonNullable<T>>,
    /** 函数名，或由方言决定写法的函数定义（例如 `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x)`）。 */
    readonly name: string | FunctionDef,
    readonly args: readonly Renderable[],
  ) {}

  /** 指定窗口，得到可选取的表达式；结果的类型接口由 T 决定（`lag(l.quantity)` 仍是小数）。 */
  over(window: (w: Window) => Window): ExprOf<T> {
    return new Over(functionCall<T>(this.type, this.name, this.args), window(Window.EMPTY)) as unknown as ExprOf<T>;
  }
}
