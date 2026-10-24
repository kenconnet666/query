package org.dpdns.zerodep.query.testing;

import org.dpdns.zerodep.query.annotation.Column;
import org.dpdns.zerodep.query.annotation.ForeignKey;
import org.dpdns.zerodep.query.annotation.Generated;
import org.dpdns.zerodep.query.annotation.Id;
import org.dpdns.zerodep.query.annotation.Index;
import org.dpdns.zerodep.query.annotation.Table;
import org.dpdns.zerodep.query.table.Col;
import org.dpdns.zerodep.query.table.Entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 结构变更的测试表：同一张 S_ITEM 的三个版本（初版、安全的变更、有风险的变更），被它引用的 S_OWNER，
 * 以及把 LIPS 当外部表的两种声明。TypeScript 的同名夹具在 test/src/fixtures/schemas.ts。
 */
public final class Schemas {
    /** 测试表所在的 schema。 */
    public static final String SCHEMA = "QUERY_IT";

    private Schemas() { }

    /** 被引用的表。 */
    @Table(schema = SCHEMA, value = "S_OWNER")
    public interface SOwner extends Entity {
        /** 编号。 */
        @Id @Column("ID") Col<Long> id();

        /** 名称。 */
        @Column(value = "NAME", length = 30) Col<String> name();
    }

    /** 初版：自增主键、唯一索引、外键、默认值和注释。 */
    @Table(schema = SCHEMA, value = "S_ITEM", comment = "items")
    @Index(name = "IX_S_ITEM_CODE", columns = "code", unique = true)
    @ForeignKey(name = "FK_S_ITEM_OWNER", columns = "ownerId", references = SOwner.class)
    public interface SItem extends Entity {
        /** 编号，数据库生成。 */
        @Id @Generated @Column("ID") Col<Long> id();

        /** 编码。 */
        @Column(value = "CODE", length = 20, nullable = false) Col<String> code();

        /** 标题。 */
        @Column(value = "TITLE", length = 40, comment = "title") Col<String> title();

        /** 价格。 */
        @Column(value = "PRICE", precision = 13, scale = 3) Col<BigDecimal> price();

        /** 状态。 */
        @Column(value = "STATUS", length = 10, nullable = false, defaultValue = "'open'") Col<String> status();

        /** 所属者。 */
        @Column("OWNER_ID") Col<Long> ownerId();

        /** 创建日期。 */
        @Column("CREATED_ON") Col<LocalDate> createdOn();
    }

    /** 安全的变更：放宽标题和价格、加一列、按旧名把 STATUS 改名为 STATE、加一个索引。 */
    @Table(schema = SCHEMA, value = "S_ITEM", comment = "items")
    @Index(name = "IX_S_ITEM_CODE", columns = "code", unique = true)
    @Index(name = "IX_S_ITEM_OWNER", columns = "ownerId")
    @ForeignKey(name = "FK_S_ITEM_OWNER", columns = "ownerId", references = SOwner.class)
    public interface SItemV2 extends Entity {
        /** 编号，数据库生成。 */
        @Id @Generated @Column("ID") Col<Long> id();

        /** 编码。 */
        @Column(value = "CODE", length = 20, nullable = false) Col<String> code();

        /** 标题，放宽到 80。 */
        @Column(value = "TITLE", length = 80, comment = "title") Col<String> title();

        /** 价格，放宽到 15 位。 */
        @Column(value = "PRICE", precision = 15, scale = 3) Col<BigDecimal> price();

        /** 状态，改名。 */
        @Column(value = "STATE", length = 10, nullable = false, defaultValue = "'open'", previousName = "STATUS") Col<String> state();

        /** 所属者。 */
        @Column("OWNER_ID") Col<Long> ownerId();

        /** 创建日期。 */
        @Column("CREATED_ON") Col<LocalDate> createdOn();

        /** 新加的备注。 */
        @Column(value = "NOTE", length = 100) Col<String> note();
    }

    /** 有风险的变更：缩窄标题、创建日期改成 NOT NULL。 */
    @Table(schema = SCHEMA, value = "S_ITEM", comment = "items")
    @Index(name = "IX_S_ITEM_CODE", columns = "code", unique = true)
    @Index(name = "IX_S_ITEM_OWNER", columns = "ownerId")
    @ForeignKey(name = "FK_S_ITEM_OWNER", columns = "ownerId", references = SOwner.class)
    public interface SItemV3 extends Entity {
        /** 编号，数据库生成。 */
        @Id @Generated @Column("ID") Col<Long> id();

        /** 编码。 */
        @Column(value = "CODE", length = 20, nullable = false) Col<String> code();

        /** 标题，缩窄到 10。 */
        @Column(value = "TITLE", length = 10, comment = "title") Col<String> title();

        /** 价格。 */
        @Column(value = "PRICE", precision = 15, scale = 3) Col<BigDecimal> price();

        /** 状态。 */
        @Column(value = "STATE", length = 10, nullable = false, defaultValue = "'open'") Col<String> state();

        /** 所属者。 */
        @Column("OWNER_ID") Col<Long> ownerId();

        /** 创建日期，改成 NOT NULL。 */
        @Column(value = "CREATED_ON", nullable = false) Col<LocalDate> createdOn();

        /** 备注。 */
        @Column(value = "NOTE", length = 100) Col<String> note();
    }

    /** 把 LIPS 当作外部表：只核对这两列存在、类型相容。 */
    @Table(schema = SCHEMA, value = "LIPS", external = true)
    public interface LipsExternal extends Entity {
        /** 交货单号。 */
        @Column("VBELN") Col<String> delivery();

        /** 数量。 */
        @Column("LFIMG") Col<BigDecimal> quantity();
    }

    /** 外部表的错误声明：一列不存在，一列类型装不下。 */
    @Table(schema = SCHEMA, value = "LIPS", external = true)
    public interface LipsBroken extends Entity {
        /** 不存在的列。 */
        @Column("NO_SUCH") Col<String> missing();

        /** 小数列当作日期读。 */
        @Column("LFIMG") Col<LocalDate> quantityAsDate();
    }
}
