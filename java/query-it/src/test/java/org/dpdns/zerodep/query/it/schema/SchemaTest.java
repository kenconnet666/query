package org.dpdns.zerodep.query.it.schema;

import org.dpdns.zerodep.query.ddl.SchemaPlan;
import org.dpdns.zerodep.query.ddl.SourceGenerator;
import org.dpdns.zerodep.query.ddl.TableDef;
import org.dpdns.zerodep.query.it.support.Live;
import org.dpdns.zerodep.query.it.support.LiveTest;
import org.dpdns.zerodep.query.testing.RenderSpec;
import org.dpdns.zerodep.query.testing.Schemas.SItem;
import org.dpdns.zerodep.query.testing.Schemas.SItemV2;
import org.dpdns.zerodep.query.testing.Schemas.SItemV3;
import org.dpdns.zerodep.query.testing.Schemas.SOwner;
import org.dpdns.zerodep.query.testing.Schemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构变更：按声明建表、从实库读回表生成列接口、安全的差异自动执行而有风险的要显式允许、外部表只核对声明的列。
 * 表声明在 {@code testing.Schemas}：S_ITEM 的三个版本（初版、安全的变更、有风险的变更）和它引用的 S_OWNER。每个测试先删掉这两张表。
 */
@LiveTest
@DisplayName("结构变更")
class SchemaTest {
    @Parameter Live live;

    @BeforeEach
    void dropTables() {
        live.execute("-DROP TABLE " + live.table("S_ITEM"), "-DROP TABLE " + live.table("S_OWNER"));   // S_ITEM 引用 S_OWNER，先删它
    }

    @Test
    void 按声明建表后与实库一致() {
        SchemaPlan create = live.db().schema().plan(SItem.class, SOwner.class);
        assertTrue(create.changes().stream().allMatch(c -> c.risk() == SchemaPlan.Risk.SAFE), create::toString);
        // 外键要么写在建表语句里（SQLite 不能事后加外键），要么在全部建表之后单独加。
        List<String> statements = create.statements();
        if (live.db().dialect().schemaSyntax().inlineForeignKeys()) {
            assertTrue(statements.stream().anyMatch(x -> x.startsWith("CREATE TABLE") && x.contains("FOREIGN KEY")), "foreign keys are inline: " + statements);
        } else {
            assertTrue(statements.get(statements.size() - 1).contains("FOREIGN KEY"), "foreign keys come last: " + statements);
        }
        create.apply();
        SchemaPlan again = live.db().schema().check(SItem.class, SOwner.class);
        assertTrue(again.isEmpty(), again::toString);
    }

    @Test
    void 从实库读回的表生成的列接口在各库上相同() throws IOException {
        // 期望的源码就放在测试源码里，编译通过即证明生成的代码可用；QUERY_SPEC_WRITE=1 时重写它。
        live.db().schema().plan(SItem.class, SOwner.class).apply();
        TableDef table = live.db().schema().read("QUERY_IT", "S_ITEM");
        String source = SourceGenerator.java(table, "org.dpdns.zerodep.query.testing.generated", SourceGenerator.typeName(table.name()), List.of());
        Path file = RenderSpec.repositoryRoot().resolve("java/query-core/src/test/java/org/dpdns/zerodep/query/testing/generated/SItemColumns.java");
        boolean comments = live.db().dialect().schemaSyntax().storesComments();
        if (comments && System.getenv("QUERY_SPEC_WRITE") != null) Files.writeString(file, source, StandardCharsets.UTF_8);
        String expected = Files.readString(file, StandardCharsets.UTF_8);
        // 不保存注释的库（SQLite）读回的表没有注释：期望的源码去掉表和列的注释再比。
        if (!comments) expected = expected.replace("（items）的列", " 的列").replace(", comment = \"title\"", "").replace("。title */", " */");
        assertEquals(expected, source);
    }

    @Test
    void 安全的差异自动执行而有风险的要显式允许() {
        live.db().schema().plan(SItem.class, SOwner.class).apply();
        // V2：放宽标题和价格、加 NOTE 列、STATUS 按旧名改成 STATE、加一个索引，五项都安全。
        SchemaPlan safe = live.db().schema().plan(SItemV2.class);
        assertEquals(5, safe.changes().size(), safe::toString);
        // SQLite 不能就地改列：放宽标题和价格只报告，改名、加列、加索引照常执行。
        boolean alters = live.db().dialect().schemaSyntax().altersColumns();
        long reported = alters ? 0 : 2;
        assertEquals(reported, safe.changes().stream().filter(c -> c.risk() == SchemaPlan.Risk.REPORT).count(), safe::toString);
        assertTrue(safe.changes().stream().allMatch(c -> c.risk() != SchemaPlan.Risk.DESTRUCTIVE), safe::toString);
        safe.apply();
        SchemaPlan clean = live.db().schema().check(SItemV2.class);
        assertEquals(reported, clean.changes().size(), clean::toString);
        assertTrue(clean.changes().stream().allMatch(c -> c.risk() == SchemaPlan.Risk.REPORT), clean::toString);
        // V3：缩窄标题、创建日期改成 NOT NULL。HANA 不能缩短列，那一项只报告；其余库是有风险的语句；SQLite 全部只报告（价格的放宽也还在）。
        SchemaPlan risky = live.db().schema().plan(SItemV3.class);
        long expected = alters ? 2 : 3;
        assertTrue(risky.changes().size() == expected && risky.changes().stream().noneMatch(c -> c.risk() == SchemaPlan.Risk.SAFE), risky::toString);
        assertEquals(List.of(), risky.statements(), "nothing runs without allowDestructive");
        assertEquals(expected, risky.pending().size());
        risky.allowDestructive().apply();
        SchemaPlan done = live.db().schema().check(SItemV3.class);
        assertTrue(done.changes().stream().allMatch(c -> c.risk() == SchemaPlan.Risk.REPORT), done::toString);
        SchemaPlan back = live.db().schema().check(SItem.class);
        assertTrue(back.changes().stream().anyMatch(c -> c.risk() == SchemaPlan.Risk.REPORT && c.message().contains("NOTE is not declared")), back::toString);
    }

    @Test
    void 外部表只核对已声明的列() {
        // 把样例数据的 LIPS 当外部表：只核对声明的两列存在、类型相容，不建也不改。
        assertTrue(live.db().schema().check(Schemas.LipsExternal.class).isEmpty());
        SchemaPlan broken = live.db().schema().check(Schemas.LipsBroken.class);
        assertEquals(2, broken.changes().size(), broken::toString);
        assertTrue(broken.changes().stream().allMatch(c -> c.risk() == SchemaPlan.Risk.REPORT && c.statements().isEmpty()));
        assertThrows(IllegalArgumentException.class, () -> live.db().schema().createTable(Schemas.LipsExternal.class));
    }
}
