package org.dpdns.zerodep.query.hana;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.expr.Nodes;
import org.dpdns.zerodep.query.meta.EntityMeta;
import org.dpdns.zerodep.query.meta.Metadata;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;
import org.dpdns.zerodep.query.select.Query;
import org.dpdns.zerodep.query.select.QueryRenderer;
import org.dpdns.zerodep.query.table.Source;
import org.dpdns.zerodep.query.type.SqlType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HANA 专属的数据来源：带参数的视图、表函数和层级。由 {@link Hana} 的工厂方法创建。 */
final class HanaSources {
    /** 只放嵌套类。 */
    private HanaSources() { }

    /** 渲染目标不是 HANA 时抛出异常。 */
    static void requireHana(SqlWriter w) {
        if (!(w.dialect() instanceof HanaDialect)) throw new UnsupportedOperationException("A HANA-only source cannot run on " + w.dialect().name());
    }

    /** 把参数值写成绑定参数，类型按值的类推断。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void bind(SqlWriter w, Object value) { w.render(new Nodes.Param<>((SqlType) SqlType.of(value.getClass()), value)); }

    /**
     * 带参数的视图或表函数：列来自表接口的声明，名字和 schema 来自 {@code @Table}。
     * 计算视图写成 {@code "S"."CV" (PLACEHOLDER."$$P$$" => ?, ...)}，表函数写成 {@code "S"."FN" ("P" => ?, ...)}；
     * 值为 null 的参数不写，由视图或函数的默认值决定。
     */
    static final class Parameterized<E> implements Source<E> {
        private final EntityMeta<E> meta;
        private final Map<String, Object> parameters;
        private final boolean function;

        Parameterized(Class<E> table, Map<String, ?> parameters, boolean function) {
            this.meta = Metadata.entity(table);
            if (!meta.isTable()) throw new IllegalArgumentException(table.getSimpleName() + " has no @Table naming the view or function");
            this.parameters = new LinkedHashMap<>(parameters);
            this.function = function;
        }

        @Override public Class<E> entity() { return meta.type(); }

        /** 写出名字和参数列表；视图没有参数时不带括号，表函数总带括号。 */
        @Override public void renderSource(SqlWriter w) {
            requireHana(w);
            w.table(meta.schema(), meta.table());
            List<Map.Entry<String, Object>> given = parameters.entrySet().stream().filter(e -> e.getValue() != null).toList();
            if (given.isEmpty() && !function) return;
            w.sql(" (");
            for (int i = 0; i < given.size(); i++) {
                if (i > 0) w.sql(", ");
                String name = given.get(i).getKey();
                if (function) w.identifier(name);
                else w.sql("PLACEHOLDER.\"$$" + name.replace("\"", "\"\"") + "$$\"");
                w.sql(" => ");
                bind(w, given.get(i).getValue());
            }
            w.sql(")");
        }

        @Override public String column(PropertyMeta property, NamingStrategy naming) { return property.columnName(naming); }

        /** 和表一样套用数据库实例的全局过滤器。 */
        @Override public boolean filtered() { return true; }
    }

    /** 层级生成函数产生的列；结果类里同名的属性读这些列，其余属性读来源查询的同名列。 */
    private static final Map<String, String> HIERARCHY_COLUMNS = Map.of(
        "hierarchyRank", "HIERARCHY_RANK", "hierarchyTreeSize", "HIERARCHY_TREE_SIZE", "hierarchyParentRank", "HIERARCHY_PARENT_RANK",
        "hierarchyRootRank", "HIERARCHY_ROOT_RANK", "hierarchyLevel", "HIERARCHY_LEVEL", "hierarchyIsCycle", "HIERARCHY_IS_CYCLE",
        "hierarchyIsOrphan", "HIERARCHY_IS_ORPHAN", "startRank", "START_RANK", "hierarchyDistance", "HIERARCHY_DISTANCE");

    /** 层级来源的种类：整棵树、某些节点的后代、某些节点的祖先。 */
    enum Navigation { TREE, DESCENDANTS, ANCESTORS }

    /**
     * {@code HIERARCHY(SOURCE (...) SIBLING ORDER BY NODE_ID)}，以及在它上面的 {@code HIERARCHY_DESCENDANTS} / {@code HIERARCHY_ANCESTORS}。
     * 来源查询要有 nodeId 和 parentId 两列（类型相同，根的 parentId 为 NULL），其余列原样带出。
     */
    static final class Hierarchy<H> implements Source<H> {
        private final Class<H> shape;
        private final Query<?> source;
        private final Navigation navigation;
        private final List<?> start;

        Hierarchy(Class<H> shape, Query<?> source, Navigation navigation, List<?> start) {
            Set<String> labels = Set.copyOf(source.labels());
            if (!labels.contains("nodeId") || !labels.contains("parentId")) {
                throw new IllegalArgumentException("A hierarchy source must select nodeId and parentId, it selects " + source.labels());
            }
            if (navigation != Navigation.TREE && start.isEmpty()) throw new IllegalArgumentException("Hierarchy navigation needs at least one start node");
            this.shape = shape;
            this.source = source;
            this.navigation = navigation;
            this.start = List.copyOf(start);
        }

        @Override public Class<H> entity() { return shape; }

        /** 写出层级函数；导航时外面再包一层 HIERARCHY_DESCENDANTS 或 HIERARCHY_ANCESTORS。 */
        @Override public void renderSource(SqlWriter w) {
            requireHana(w);
            if (navigation != Navigation.TREE) w.sql(navigation == Navigation.DESCENDANTS ? "HIERARCHY_DESCENDANTS(SOURCE " : "HIERARCHY_ANCESTORS(SOURCE ");
            String s = w.aliasFor(new Object(), "src");
            w.sql("HIERARCHY(SOURCE (SELECT ").alias(s).sql(".*, ").alias(s).sql(".").identifier("nodeId").sql(" AS NODE_ID, ")
                .alias(s).sql(".").identifier("parentId").sql(" AS PARENT_ID FROM (");
            QueryRenderer.named(w, source);
            w.sql(") ").alias(s).sql(") SIBLING ORDER BY NODE_ID ASC)");
            if (navigation == Navigation.TREE) return;
            w.sql(" START WHERE NODE_ID IN (");
            for (int i = 0; i < start.size(); i++) {
                if (i > 0) w.sql(", ");
                bind(w, start.get(i));
            }
            w.sql("))");
        }

        @Override public String column(PropertyMeta property, NamingStrategy naming) { return HIERARCHY_COLUMNS.getOrDefault(property.name(), property.name()); }

        @Override public String preferredAlias() { return "hierarchy"; }
    }
}
