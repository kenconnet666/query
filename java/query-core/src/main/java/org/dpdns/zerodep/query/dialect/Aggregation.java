package org.dpdns.zerodep.query.dialect;

/**
 * 一个表达式在外层查询里是逐行计算、聚合（多行合成一行）还是窗口函数（行数不变）：按结果列筛选时决定写进 WHERE 还是 HAVING，
 * 数总行数时决定能不能直接把投影换成 COUNT(*)。
 *
 * <p>做法是用探查写入器把表达式写一遍，聚合与窗口函数渲染时把自己报到这里：{@code Aggregate}、标了
 * {@link FunctionDef#aggregating()} 的函数（MEDIAN、STRING_AGG 等）调用 {@link #aggregate}，窗口函数用 {@link #window} 包住 OVER 的写法，
 * 渲染器写子查询时用 {@link #nested} 包住。窗口函数里、子查询里的聚合不算外层的。普通的渲染不带探查，这些调用什么也不做。
 */
public final class Aggregation {
    /** 表达式怎样作用于行。 */
    public enum Kind {
        /** 逐行计算。 */
        PLAIN,
        /** 聚合：多行合成一行。 */
        AGGREGATE,
        /** 窗口函数：每行一个值，行数不变。 */
        WINDOW
    }

    /** 一次探查的结果和当前所在的层次。 */
    private static final class Tracker {
        int nested;
        int windowed;
        boolean aggregate;
        boolean window;
    }

    /** 写入器状态的键。 */
    private static final Object KEY = new Object();

    private Aggregation() { }

    /** node 在外层查询里是哪一种；窗口函数优先（窗口化的聚合也是窗口函数）。 */
    public static Kind kindOf(SqlWriter w, Renderable node) {
        SqlWriter probe = w.probe();
        Tracker t = new Tracker();
        probe.state(KEY, t);
        probe.render(node);
        return t.window ? Kind.WINDOW : t.aggregate ? Kind.AGGREGATE : Kind.PLAIN;
    }

    /** 聚合函数渲染时调用。 */
    public static void aggregate(SqlWriter w) {
        if (w.state(KEY) instanceof Tracker t && t.nested == 0 && t.windowed == 0) t.aggregate = true;
    }

    /** 窗口函数用它包住 OVER 的写法：里面的聚合是窗口化的，不算外层的聚合。 */
    public static void window(SqlWriter w, Runnable over) {
        if (!(w.state(KEY) instanceof Tracker t)) {
            over.run();
            return;
        }
        if (t.nested == 0) t.window = true;
        t.windowed++;
        try {
            over.run();
        } finally {
            t.windowed--;
        }
    }

    /** 渲染器写子查询时用它包住：子查询里的聚合属于子查询自己。 */
    public static void nested(SqlWriter w, Runnable subquery) {
        if (!(w.state(KEY) instanceof Tracker t)) {
            subquery.run();
            return;
        }
        t.nested++;
        try {
            subquery.run();
        } finally {
            t.nested--;
        }
    }
}
