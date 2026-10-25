package org.dpdns.zerodep.query.exec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("树形数据")
class TreesTest {
    record Unit(Integer id, Integer parentId, String name) { }

    record Node(String name, List<Node> children) {
        /** 叶子是名字，有子节点的是 名字 → 子节点。 */
        Object shape() { return children.isEmpty() ? name : Map.of(name, children.stream().map(Node::shape).toList()); }
    }

    /** 1 研发 → 2 前端、3 后端 → 4 平台；5 采购。 */
    private static final List<Unit> UNITS = List.of(new Unit(4, 3, "平台"), new Unit(1, null, "研发"), new Unit(2, 1, "前端"),
        new Unit(3, 1, "后端"), new Unit(5, null, "采购"));

    private static List<Object> shape(List<Unit> rows) {
        return Trees.<Unit, Integer, Node>build(rows, Unit::id, Unit::parentId, (u, kids) -> new Node(u.name(), kids)).stream().map(Node::shape).toList();
    }

    @Test
    void build同级保持原来的顺序_父行不在这批里的当作根() {
        assertEquals(List.of(Map.of("研发", List.of("前端", Map.of("后端", List.of("平台")))), "采购"), shape(UNITS));
        assertEquals(List.of("平台"), shape(UNITS.subList(0, 1)));
    }

    @Test
    void build键重复或成环时报错() {
        assertThrows(IllegalArgumentException.class, () -> shape(List.of(UNITS.get(0), UNITS.get(0))));
        assertThrows(IllegalArgumentException.class, () -> shape(List.of(new Unit(1, 2, "a"), new Unit(2, 1, "b"))));
    }

    @Test
    void withAncestors一层一层按键补上祖先_已有的不重复取() {
        List<List<Integer>> asked = new ArrayList<>();
        List<Unit> rows = Trees.withAncestors(List.of(UNITS.get(0), UNITS.get(2)), Unit::id, Unit::parentId, ids -> {
            asked.add(List.copyOf(ids));
            return UNITS.stream().filter(u -> ids.contains(u.id())).toList();
        });
        assertEquals(List.of("平台", "前端", "研发", "后端"), rows.stream().map(Unit::name).toList(), "补上的祖先按 load 返回的顺序");
        assertEquals(List.of(List.of(3, 1)), asked);
        assertEquals(List.of(Map.of("研发", List.of("前端", Map.of("后端", List.of("平台"))))), shape(rows));
    }

    @Test
    void withAncestors父行不存在时停下_键多时分批() {
        List<Unit> orphans = IntStream.range(0, Trees.BATCH + 3).mapToObj(i -> new Unit(1000 + i, 5000 + i, "x" + i)).toList();
        List<Integer> sizes = new ArrayList<>();
        assertEquals(orphans.size(), Trees.withAncestors(orphans, Unit::id, Unit::parentId, ids -> { sizes.add(ids.size()); return List.of(); }).size());
        assertEquals(List.of(Trees.BATCH, 3), sizes);
    }
}
