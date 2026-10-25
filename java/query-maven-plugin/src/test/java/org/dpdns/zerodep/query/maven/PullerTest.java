package org.dpdns.zerodep.query.maven;

import org.dpdns.zerodep.query.ddl.ColumnType;
import org.dpdns.zerodep.query.ddl.TableBuilder;
import org.dpdns.zerodep.query.ddl.TableDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("query:pull 写出源码")
class PullerTest {
    private static final Map<String, TableDef> TABLES = Map.of(
        "LIPS", TableBuilder.table("SAPHANADB", "LIPS").column("VBELN", ColumnType.text(10)).notNull().column("POSNR", ColumnType.text(6)).notNull()
            .column("LFIMG", ColumnType.decimal(13, 3)).primaryKey("VBELN", "POSNR").build(),
        "MARA", TableBuilder.table("SAPHANADB", "MARA").column("MATNR", ColumnType.text(40)).notNull().primaryKey("MATNR").build());

    @TempDir Path dir;

    private Puller.Config config(Set<String> only, Puller.Table... tables) {
        return new Puller.Config("hana", "", "", "", "SAPHANADB", "com.example.gen", dir, List.of(tables), only);
    }

    @Test
    void 按包写出接口且内容不变时不重写() throws IOException {
        Puller.Config c = config(Set.of(), new Puller.Table("LIPS", null, List.of("VBELN", "LFIMG")), new Puller.Table("MARA", "Material", List.of()));
        List<Path> first = Puller.write(c, TABLES::get);
        assertEquals(List.of(dir.resolve("com/example/gen/LipsColumns.java"), dir.resolve("com/example/gen/Material.java")), first);
        String lips = Files.readString(first.get(0));
        assertTrue(lips.contains("@Id @Column(value = \"VBELN\", length = 10) ColText<String> vbeln();"), lips);
        assertTrue(lips.contains("@Column(value = \"LFIMG\", precision = 13, scale = 3) ColDecimal<BigDecimal> lfimg();"), lips);
        assertTrue(!lips.contains("POSNR"), lips);
        assertEquals(List.of(), Puller.write(c, TABLES::get));
    }

    @Test
    void only只重新生成列出的表() throws IOException {
        Puller.Config c = config(Set.of("mara"), new Puller.Table("LIPS", null, List.of()), new Puller.Table("MARA", null, List.of()));
        assertEquals(List.of(dir.resolve("com/example/gen/MaraColumns.java")), Puller.write(c, TABLES::get));
    }

    @Test
    void 表或列不存在时报错() {
        assertThrows(IllegalArgumentException.class, () -> Puller.write(config(Set.of(), new Puller.Table("NO_SUCH", null, List.of())), TABLES::get));
        assertThrows(IllegalArgumentException.class, () -> Puller.write(config(Set.of(), new Puller.Table("MARA", null, List.of("NO_SUCH"))), TABLES::get));
    }
}
