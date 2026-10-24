package org.dpdns.zerodep.query.meta;

import java.util.List;

/**
 * 表接口上用 {@code @ManyToOne} 或 {@code @OneToMany} 声明的一个关系。
 *
 * @param name   关系方法名
 * @param many   是否一对多
 * @param target 目标表接口
 * @param self   本表参与连接的列方法名
 * @param keys   目标表参与连接的列方法名，与 self 一一对应
 */
public record RelationMeta(String name, boolean many, Class<?> target, List<String> self, List<String> keys) {
    /** 复制列表，并检查两边的列数一致。 */
    public RelationMeta {
        self = List.copyOf(self);
        keys = List.copyOf(keys);
        if (self.isEmpty() || self.size() != keys.size()) {
            throw new IllegalArgumentException(name + "(): self and target must name the same number of columns, at least one");
        }
    }
}
