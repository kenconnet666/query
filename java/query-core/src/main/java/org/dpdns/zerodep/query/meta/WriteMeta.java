package org.dpdns.zerodep.query.meta;

import java.util.List;
import java.util.Set;

/**
 * 表接口上与写入有关的列，取自 {@code @Id}、{@code @Version}、{@code @Generated}、{@code @LogicDelete}、{@code @ReadOnly}、
 * {@code @WriteOnce}；没有的为空。
 *
 * @param ids         主键列的属性名，按名称排序
 * @param version     版本列的属性名，没有时为 null
 * @param generated   数据库生成值的列的属性名，没有时为 null
 * @param logicDelete 逻辑删除列，没有时为 null
 * @param readOnly    只读列的属性名：插入和更新都不写
 * @param writeOnce   只在插入时写的列的属性名
 */
public record WriteMeta(List<String> ids, String version, String generated, LogicDeleteMeta logicDelete, Set<String> readOnly, Set<String> writeOnce) {
    /** 没有任何写入相关注解的表，以及结果类和行类。 */
    public static final WriteMeta NONE = new WriteMeta(List.of(), null, null, null, Set.of(), Set.of());

    /** 复制集合。 */
    public WriteMeta {
        ids = List.copyOf(ids);
        readOnly = Set.copyOf(readOnly);
        writeOnce = Set.copyOf(writeOnce);
    }

    /** 插入时是否写这个属性：生成列和只读列不写。 */
    public boolean inserts(String property) { return !property.equals(generated) && !readOnly.contains(property); }

    /** {@code byId} 更新和插入或更新的更新部分是否写这个属性：主键、版本、生成列、只读列和只在插入时写的列都不写。 */
    public boolean updates(String property) {
        return !ids.contains(property) && !property.equals(version) && inserts(property) && !writeOnce.contains(property);
    }

    /**
     * 逻辑删除列。
     *
     * @param property 列的属性名
     * @param active   未删除时的值；timestamp 为 true 时不用
     * @param deleted  删除后的值；timestamp 为 true 时不用
     * @param timestamp 是否是记录删除时间的列：未删除为 NULL，删除时写当前时间
     */
    public record LogicDeleteMeta(String property, Object active, Object deleted, boolean timestamp) { }
}
