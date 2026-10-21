package org.dpdns.zerodep.query.testing;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;
import org.dpdns.zerodep.query.annotation.BlankAsNull;
import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ConvertWith;
import org.dpdns.zerodep.query.annotation.Ignore;
import org.dpdns.zerodep.query.annotation.MapFrom;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.annotation.Trimmed;
import org.dpdns.zerodep.query.type.Converter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** SAP-style test entities shared by the unit tests and the live-database scenarios of every dialect. */
public final class Erp {
    public static final String SCHEMA = "QUERY_IT";

    private Erp() { }

    // ---------- a mounted type ----------

    /** SAP keeps dates as NVARCHAR(8) 'yyyyMMdd'; '' and '00000000' mean no date. */
    public static final class SapDateConverter implements Converter<LocalDate, String> {
        @Override public String toDatabase(LocalDate value) { return value.format(DateTimeFormatter.BASIC_ISO_DATE); }

        @Override public LocalDate fromDatabase(String value) {
            String v = value.trim();
            return v.isEmpty() || v.equals("00000000") ? null : LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @ConvertWith(SapDateConverter.class)
    public @interface SapDate { }

    // ---------- tables ----------

    @Data
    public abstract static class ClientEntity {
        @Column("MANDT") private String client;
    }

    @Data @EqualsAndHashCode(callSuper = true) @FieldNameConstants
    @Table(schema = SCHEMA, value = "MARA")
    public static class Mara extends ClientEntity {
        @Column("MATNR") private String material;
        @Column("MFRPN") private String partNumber;
        @Column("MFRNR") private String manufacturer;
        @Column("MTART") private String materialType;
    }

    /** MARA read the way SAP data is used: trimmed part numbers, blank makers as NULL, and the stored maker. */
    @Data @EqualsAndHashCode(callSuper = true)
    @Table(schema = SCHEMA, value = "MARA")
    public static class MaraView extends ClientEntity {
        @Column("MATNR") private String material;
        @Column("MFRPN") @Trimmed private String partNumber;
        @Column("MFRNR") @BlankAsNull private String maker;
        @Column("MFRNR") private String storedMaker;
    }

    @Data @EqualsAndHashCode(callSuper = true) @FieldNameConstants
    @Table(schema = SCHEMA, value = "LIPS")
    public static class Lips extends ClientEntity {
        @Column("VBELN") private String delivery;
        @Column("MATNR") private String material;
        @Column("ERDAT") @SapDate private LocalDate createdOn;
        @Column("LFIMG") private BigDecimal quantity;
        @Column("VRKME") private String unit;
        @Column("VGBEL") private String salesOrder;
    }

    @Data @EqualsAndHashCode(callSuper = true)
    @Table(schema = SCHEMA, value = "MAKT")
    public static class Makt extends ClientEntity {
        @Column("MATNR") private String material;
        @Column("SPRAS") private String language;
        @Column("MAKTX") private String text;
    }

    @Data @EqualsAndHashCode(callSuper = true)
    @Table(schema = SCHEMA, value = "VBKD")
    public static class Vbkd extends ClientEntity {
        @Column("VBELN") private String salesOrder;
    }

    /** The shared shape of the monthly stock history tables; Lombok getters implement it. */
    public interface PeriodStock {
        String getClient();

        String getMaterial();

        String getYear();

        String getPeriod();
    }

    @Data @EqualsAndHashCode(callSuper = true)
    @Table(schema = SCHEMA, value = "MARDH")
    public static class Mardh extends ClientEntity implements PeriodStock {
        @Column("MATNR") private String material;
        @Column("LFGJA") private String year;
        @Column("LFMON") private String period;
        @Column("LABST") private BigDecimal unrestricted;
    }

    @Data @EqualsAndHashCode(callSuper = true)
    @Table(schema = SCHEMA, value = "MCHBH")
    public static class Mchbh extends ClientEntity implements PeriodStock {
        @Column("MATNR") private String material;
        @Column("LFGJA") private String year;
        @Column("LFMON") private String period;
        @Column("CLABS") private BigDecimal batchUnrestricted;
    }

    /** Real DATE and TIMESTAMP columns, for time-zone checks. */
    @Data
    @Table(schema = SCHEMA, value = "EVENTS")
    public static class Event {
        @Column("ID") private Integer id;
        @Column("ON_DAY") private LocalDate onDay;
        @Column("AT_TIME") private LocalDateTime atTime;
    }

    // ---------- shapes and results ----------

    /** A temporary entity: the row of a CTE. */
    public record MaterialScope(String client, String material, String partNumber) { }

    public record Delivery(String partNumber, String name, BigDecimal quantity) { }

    @Data @Accessors(chain = true)
    public static class DeliveryVO {
        private String partNumber;
        private String name;
        private Double quantity;
    }

    @Builder @lombok.Value
    public static class DeliveryView {
        String partNumber;
        BigDecimal quantity;
    }

    /** Same-named columns map automatically; a renamed one names its source with a Lombok constant. */
    @Data
    public static class LineVO {
        private String delivery;
        @MapFrom(value = Lips.class, property = Lips.Fields.material) private String materialNo;
        private LocalDate createdOn;
        private String text;
        @Ignore private String computedLater;
    }

    public record StockRow(String material, String year, String period, BigDecimal quantity) { }

    public record PartTotal(String partNumber, Amount amount) {
        public record Amount(BigDecimal quantity, double wan) { }
    }
}
