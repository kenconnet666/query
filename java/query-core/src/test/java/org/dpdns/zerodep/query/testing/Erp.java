package org.dpdns.zerodep.query.testing;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;
import org.dpdns.zerodep.query.annotation.BlankAsNull;
import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ConvertWith;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.ManyToOne;
import org.dpdns.zerodep.query.annotation.OneToMany;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Trimmed;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;
import org.dpdns.zerodep.query.table.Many;
import org.dpdns.zerodep.query.type.Converter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.dpdns.zerodep.query.Sql.all;

/** SAP 风格的测试表与结果类，单元测试和实库测试共用；实库测试的样例数据见 query-it 的 SampleData。 */
public final class Erp {
    public static final String SCHEMA = "QUERY_IT";

    private Erp() { }

    // ---------- 挂载的类型 ----------

    /** SAP 把日期存成 NVARCHAR(8) 的 'yyyyMMdd'；'' 和 '00000000' 表示没有日期。 */
    public static final class SapDateConverter implements Converter<LocalDate, String> {
        /** 日期写成 yyyyMMdd。 */
        @Override public String toDatabase(LocalDate value) { return value.format(DateTimeFormatter.BASIC_ISO_DATE); }

        /** yyyyMMdd 读成日期，空值读成 null。 */
        @Override public LocalDate fromDatabase(String value) {
            String v = value.trim();
            return v.isEmpty() || v.equals("00000000") ? null : LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE);
        }
    }

    /** 标在列方法（或原生 SQL 的结果字段）上，按 SAP 日期读写。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.FIELD})
    @ConvertWith(SapDateConverter.class)
    public @interface SapDate { }

    // ---------- 表 ----------

    /** 各表共有的集团列，全局过滤器按它匹配。 */
    public interface Client extends Entity {
        /** 集团。 */
        @Column("MANDT") Col<String> client();
    }

    /** 物料主数据。 */
    @Table(schema = SCHEMA, value = "MARA")
    public interface Mara extends Client {
        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 制造商零件号。 */
        @Column("MFRPN") Col<String> partNumber();

        /** 制造商。 */
        @Column("MFRNR") Col<String> manufacturer();

        /** 物料类型。 */
        @Column("MTART") Col<String> materialType();

        /** 一对多：这个物料的交货行（同一集团）。 */
        default Many<Lips> deliveries() { return many(Lips.class, l -> all(l.material().eq(material()), l.client().eq(client()))); }

        /** 多对一：语言 1 的物料描述。 */
        default Makt text() { return one(Makt.class, t -> all(t.material().eq(material()), t.client().eq(client()), t.language().eq("1"))); }
    }

    /** 按 SAP 数据的实际用法读 MARA：零件号去空格、空制造商读作 NULL，同时保留原值列。 */
    @Table(schema = SCHEMA, value = "MARA")
    public interface MaraView extends Client {
        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 去掉首尾空格的零件号。 */
        @Column("MFRPN") @Trimmed Col<String> partNumber();

        /** 空串读作 NULL 的制造商。 */
        @Column("MFRNR") @BlankAsNull Col<String> maker();

        /** 制造商的原值。 */
        @Column("MFRNR") Col<String> storedMaker();
    }

    /** 交货单行项目。 */
    @Table(schema = SCHEMA, value = "LIPS")
    public interface Lips extends Client {
        /** 交货单号。 */
        @Column("VBELN") Col<String> delivery();

        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 创建日期（SAP 日期）。 */
        @Column("ERDAT") @SapDate Col<LocalDate> createdOn();

        /** 交货数量。 */
        @Column(value = "LFIMG", precision = 13, scale = 3) Col<BigDecimal> quantity();

        /** 单位。 */
        @Column("VRKME") Col<String> unit();

        /** 参考的销售订单号。 */
        @Column("VGBEL") Col<String> salesOrder();

        /** 多对一：物料主数据（同一集团）。 */
        default Mara mara() { return one(Mara.class, m -> all(m.material().eq(material()), m.client().eq(client()))); }

        /** 多对一：语言 1 的物料描述。 */
        default Makt text() { return one(Makt.class, t -> all(t.material().eq(material()), t.client().eq(client()), t.language().eq("1"))); }

        /** 一对多（注解写法）：参考的销售订单的业务数据。 */
        @OneToMany(self = "salesOrder", target = "salesOrder") Many<Vbkd> salesData();

        /** 多对一（注解写法）：任意语言的物料描述，只用于渲染测试。 */
        @ManyToOne(self = {"client", "material"}, target = {"client", "material"}) Makt anyText();
    }

    /** 物料描述。 */
    @Table(schema = SCHEMA, value = "MAKT")
    public interface Makt extends Client {
        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 语言。 */
        @Column("SPRAS") Col<String> language();

        /** 描述文本。 */
        @Column("MAKTX") Col<String> text();
    }

    /** 销售凭证的业务数据。 */
    @Table(schema = SCHEMA, value = "VBKD")
    public interface Vbkd extends Client {
        /** 销售订单号。 */
        @Column("VBELN") Col<String> salesOrder();
    }

    /** 月度库存历史表的共同形状；数量列在各表的列名不同，由各表重新声明。 */
    public interface PeriodStock extends Client {
        /** 物料号。 */
        @Column("MATNR") Col<String> material();

        /** 年度。 */
        @Column("LFGJA") Col<String> year();

        /** 期间（月）。 */
        @Column("LFMON") Col<String> period();

        /** 库存数量。 */
        Col<BigDecimal> quantity();
    }

    /** 物料的月度库存历史。 */
    @Table(schema = SCHEMA, value = "MARDH")
    public interface Mardh extends PeriodStock {
        /** 非限制库存。 */
        @Override @Column(value = "LABST", precision = 13, scale = 3) Col<BigDecimal> quantity();
    }

    /** 批次的月度库存历史。 */
    @Table(schema = SCHEMA, value = "MCHBH")
    public interface Mchbh extends PeriodStock {
        /** 批次的非限制库存。 */
        @Override @Column(value = "CLABS", precision = 13, scale = 3) Col<BigDecimal> quantity();
    }

    /** 真正的 DATE 与 TIMESTAMP 列，用于检查时区。 */
    @Table(schema = SCHEMA, value = "EVENTS")
    public interface Event extends Entity {
        /** 编号。 */
        @Column("ID") Col<Integer> id();

        /** 日期。 */
        @Column("ON_DAY") Col<LocalDate> onDay();

        /** 时间戳。 */
        @Column("AT_TIME") Col<LocalDateTime> atTime();
    }

    /** 类名是保留字的表，生成的别名必须加引号。 */
    @Table(schema = SCHEMA, value = "LIPS")
    public interface Order extends Entity {
        /** 交货单号。 */
        @Column("VBELN") Col<String> delivery();

        /** 物料号。 */
        @Column("MATNR") Col<String> material();
    }

    // ---------- 行与结果 ----------

    /** CTE 的一行。 */
    public record MaterialScope(String client, String material, String partNumber) { }

    /** EVENTS 的一行。 */
    public record EventRow(Integer id, LocalDate onDay, LocalDateTime atTime) { }

    /** 递归 CTE 的一行：交货单与展开的层数。 */
    public record Step(String delivery, Integer depth) { }

    /** 交货汇总的一行。 */
    public record Delivery(String partNumber, String name, BigDecimal quantity) { }

    /** 链式 setter 的结果类。 */
    @Data @Accessors(chain = true)
    public static class DeliveryVO {
        private String partNumber;
        private String name;
        private Double quantity;
    }

    /** 用 Builder 构造的结果类。 */
    @Builder @lombok.Value
    public static class DeliveryView {
        String partNumber;
        BigDecimal quantity;
    }

    /** 行项目的结果类；computedLater 不参与映射。 */
    @Data
    public static class LineVO {
        private String delivery;
        private String materialNo;
        private LocalDate createdOn;
        private String text;
        @Ignore private String computedLater;
    }

    /** 库存 UNION 的一行。 */
    public record StockRow(String material, String year, String period, BigDecimal quantity) { }

    /** 嵌套的结果：零件号加数量与折算的万单位。 */
    public record PartTotal(String partNumber, Amount amount) {
        /** 数量和以万为单位的数量。 */
        public record Amount(BigDecimal quantity, double wan) { }
    }
}
