package org.dpdns.zerodep.query.select;

import org.dpdns.zerodep.query.dialect.Renderable;
import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Cond;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.table.Ref;
import org.dpdns.zerodep.query.table.SourceFilters;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 导航关系的隐式连接：找出一段 SQL 真正用到的关系，再把它们写成 LEFT JOIN。查询和写语句（UPDATE、DELETE 的条件）共用。
 */
public final class ImplicitJoins {
    private ImplicitJoins() { }

    /**
     * roots 出发的关系里，parts 渲染时真正用到的那些。先用探查写入器把 parts 试渲染一遍，记下引用到的数据源；
     * 用到了某个关系的目标（或它更深的关系）就连接它，并把它的连接条件也试渲染，直到不再增加。没有关系时不做探查。
     */
    public static Set<Ref.Relation> used(SqlWriter w, List<? extends Ref<?>> roots, List<? extends Renderable> parts) {
        if (roots.stream().allMatch(r -> r.relations().isEmpty())) return Set.of();
        SqlWriter probe = w.probe();
        for (Renderable p : parts) {
            if (!(p instanceof Cond c) || !c.isEmpty()) probe.render(p);
        }
        Set<Ref.Relation> used = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Ref<?> root : roots) grew |= collect(root, probe, used);
        }
        return used;
    }

    /** 紧跟在 ref 的 FROM 或 JOIN 之后写出它用到的隐式连接（LEFT JOIN），再写更深的关系；全局过滤器放进 ON。 */
    public static void emit(SqlWriter w, Ref<?> ref, Set<Ref.Relation> used, SourceFilters filters) {
        for (Ref.Relation r : ref.relations()) {
            if (!used.contains(r)) continue;
            w.sql("\nLEFT JOIN ");
            r.target().renderSource(w);
            List<Cond> on = new ArrayList<>();
            on.add(r.on());
            on.addAll(filters.conditionsFor(r.target()));
            w.sql(" ON ").render(Nodes.all(on));
            emit(w, r.target(), used, filters);
        }
    }

    /** 把 ref 出发的、用到了的关系加入 used，并试渲染它们的连接条件；有新增时返回 true。 */
    private static boolean collect(Ref<?> ref, SqlWriter probe, Set<Ref.Relation> used) {
        boolean grew = false;
        for (Ref.Relation r : ref.relations()) {
            if (!used.contains(r) && reaches(r.target(), probe.touched())) {
                used.add(r);
                probe.render(r.on());
                grew = true;
            }
            if (used.contains(r)) grew |= collect(r.target(), probe, used);
        }
        return grew;
    }

    /** ref 或从它出发的某个关系的目标是否被引用到。 */
    private static boolean reaches(Ref<?> ref, Set<Object> touched) {
        if (touched.contains(ref)) return true;
        for (Ref.Relation r : ref.relations()) if (reaches(r.target(), touched)) return true;
        return false;
    }
}
