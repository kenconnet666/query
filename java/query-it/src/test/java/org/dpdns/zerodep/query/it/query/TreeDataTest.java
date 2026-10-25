package org.dpdns.zerodep.query.it.query;

import org.dpdns.zerodep.query.exec.Trees;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.testing.Org.Unit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.dpdns.zerodep.query.Sql.bool;
import static org.dpdns.zerodep.query.Sql.cols;
import static org.dpdns.zerodep.query.Sql.from;
import static org.dpdns.zerodep.query.Sql.insertInto;
import static org.dpdns.zerodep.query.Sql.row;
import static org.dpdns.zerodep.query.it.support.Db.HANA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 树形数据：懒加载的第一层带 hasChildren，服务端搜索补上祖先再组装成树。每个库建一次表，写入（与 TypeScript 的 tree.live.test.ts 相同）：
 *
 * <pre>
 * ORG_UNIT  1 研发（根）→ 2 前端、3 后端 → 4 平台；5 采购（根）
 * </pre>
 */
@LiveTest
@DisplayName("树形数据")
class TreeDataTest {
    @Parameter Live live;

    record UnitRow(Integer id, Integer parentId, String name) { }

    record Root(String name, Boolean hasChildren) { }

    record Node(String name, List<Node> children) {
        /** 叶子是名字，有子节点的是 名字 → 子节点。 */
        Object shape() { return children.isEmpty() ? name : Map.of(name, children.stream().map(Node::shape).toList()); }
    }

    @BeforeParameterizedClassInvocation
    static void tables(Live live) {
        live.recreate(Unit.class);
        live.database(null).execute(insertInto(Unit.class).values(new UnitRow(1, null, "研发"), new UnitRow(2, 1, "前端"), new UnitRow(3, 1, "后端"),
            new UnitRow(4, 3, "平台"), new UnitRow(5, null, "采购")));
    }

    /** 满足条件的单元，按键排序。 */
    private List<UnitRow> units(Consumer<Unit> where) {
        return live.db().list(from(Unit.class).where(u -> where.accept(u)).orderBy(u -> u.id()).select(u -> cols(u.id(), u.parentId(), u.name())).into(UnitRow.class));
    }

    @Test
    void 懒加载时每行带上有没有子节点() {
        List<Root> roots = live.db().list(from(Unit.class).where(u -> u.parentId().isNull()).orderBy(u -> u.id())
            .select(u -> cols(u.name(), bool(() -> u.children().any()).as("hasChildren"))).into(Root.class));
        assertEquals(List.of(new Root("研发", true), new Root("采购", false)), roots);
    }

    @Test
    void withAncestors在有递归CTE的库上一条语句取出命中的行和祖先() {
        var events = live.db().capture(() -> {
            List<UnitRow> rows = live.db().withAncestors(Unit.class, u -> u.id(), u -> u.parentId(), u -> u.name().contains("平台"),
                u -> row(UnitRow::new, u.id(), u.parentId(), u.name()));
            List<Node> tree = Trees.build(rows, UnitRow::id, UnitRow::parentId, (u, kids) -> new Node(u.name(), kids));
            assertEquals(List.of(Map.of("研发", List.of(Map.of("后端", List.of("平台"))))), tree.stream().map(Node::shape).toList());
        });
        // HANA 没有递归 CTE：命中的键、两层祖先的键，再取一次列
        assertEquals(live.is(HANA) ? 4 : 1, events.size(), events.toString());
    }

    @Test
    void 搜索命中的行补上祖先_组装成从根开始的树() {
        List<UnitRow> rows = Trees.withAncestors(units(u -> u.name().contains("平台")), UnitRow::id, UnitRow::parentId, ids -> units(u -> u.id().in(ids)));
        List<Node> tree = Trees.build(rows, UnitRow::id, UnitRow::parentId, (u, kids) -> new Node(u.name(), kids));
        assertEquals(List.of(Map.of("研发", List.of(Map.of("后端", List.of("平台"))))), tree.stream().map(Node::shape).toList());
    }
}
