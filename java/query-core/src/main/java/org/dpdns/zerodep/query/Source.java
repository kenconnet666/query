package org.dpdns.zerodep.query;

import org.dpdns.zerodep.query.dialect.SqlWriter;
import org.dpdns.zerodep.query.meta.NamingStrategy;
import org.dpdns.zerodep.query.meta.PropertyMeta;

import java.util.Locale;

/**
 * 查询读取行的来源：表、CTE、派生表。实现它可以增加新的来源种类，例如某个方言的参数化视图。
 *
 * @param <E> 其属性描述行结构的类
 */
public interface Source<E> {
    /** 描述行结构的类；它的属性就是来源的列。 */
    Class<E> entity();

    /** 写出 FROM 或 JOIN 里的来源，不含别名。 */
    void renderSource(SqlWriter w);

    /** 属性在这个来源里的列名。 */
    String column(PropertyMeta property, NamingStrategy naming);

    /** 优先使用的别名，默认是类的简单名小写；重名时由写入器加序号。 */
    default String preferredAlias() { return entity().getSimpleName().toLowerCase(Locale.ROOT); }

    /** 数据库实例级的全局过滤器是否作用于这个来源；表为 true。 */
    default boolean filtered() { return false; }
}
