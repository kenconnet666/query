import type { Renderable, SqlWriter } from "./writer.js";

/**
 * 一个表达式在外层查询里是逐行计算、聚合（多行合成一行）还是窗口函数（行数不变）：按结果列筛选时决定写进 WHERE 还是 HAVING，
 * 数总行数时决定能不能直接把投影换成 COUNT(*)。
 *
 * 做法是用探查写入器把表达式写一遍，聚合与窗口函数渲染时把自己报到这里：`Aggregate`、标了 `aggregating()` 的函数
 * （MEDIAN、STRING_AGG 等）调用 {@link noteAggregate}，窗口函数用 {@link windowed} 包住 OVER 的写法，渲染器写子查询时用
 * {@link nested} 包住。窗口函数里、子查询里的聚合不算外层的。普通的渲染不带探查，这些调用什么也不做。与 Java 的 `Aggregation` 相同。
 */
export type RowKind = "plain" | "aggregate" | "window";

/** 一次探查的结果和当前所在的层次。 */
interface Tracker {
  nested: number;
  windowed: number;
  aggregate: boolean;
  window: boolean;
}

/** 写入器状态的键。 */
const KEY = Symbol("aggregation");

/** node 在外层查询里是哪一种；窗口函数优先（窗口化的聚合也是窗口函数）。 */
export function rowKindOf(w: SqlWriter, node: Renderable): RowKind {
  const probe = w.probe();
  const t: Tracker = { nested: 0, windowed: 0, aggregate: false, window: false };
  probe.setState(KEY, t);
  probe.render(node);
  return t.window ? "window" : t.aggregate ? "aggregate" : "plain";
}

/** 聚合函数渲染时调用。 */
export function noteAggregate(w: SqlWriter): void {
  const t = w.state(KEY) as Tracker | undefined;
  if (t !== undefined && t.nested === 0 && t.windowed === 0) t.aggregate = true;
}

/** 窗口函数用它包住 OVER 的写法：里面的聚合是窗口化的，不算外层的聚合。 */
export function windowed(w: SqlWriter, over: () => void): void {
  const t = w.state(KEY) as Tracker | undefined;
  if (t === undefined) return over();
  if (t.nested === 0) t.window = true;
  t.windowed++;
  try {
    over();
  } finally {
    t.windowed--;
  }
}

/** 渲染器写子查询时用它包住：子查询里的聚合属于子查询自己。 */
export function nested(w: SqlWriter, subquery: () => void): void {
  const t = w.state(KEY) as Tracker | undefined;
  if (t === undefined) return subquery();
  t.nested++;
  try {
    subquery();
  } finally {
    t.nested--;
  }
}
