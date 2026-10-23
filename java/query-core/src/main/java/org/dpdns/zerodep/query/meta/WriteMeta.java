package org.dpdns.zerodep.query.meta;

import java.util.List;

/**
 * 表接口上与写入有关的列，取自 {@code @Id}、{@code @Version}、{@code @Generated}、{@code @LogicDelete}；没有的为空。
 *
 * @param ids         主键列的属性名，按名称排序
 * @param version     版本列的属性名，没有时为 null
 * @param generated   数据库生成值的列的属性名，没有时为 null
 * @param logicDelete 逻辑删除列，没有时为 null
 */
public record WriteMeta(List<String> ids, String version, String generated, LogicDeleteMeta logicDelete) {
    /** 没有任何写入相关注解的表，以及结果类和行类。 */
    public static final WriteMeta NONE = new WriteMeta(List.of(), null, null, null);

    /** 复制主键列表。 */
    public WriteMeta {
        ids = List.copyOf(ids);
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
