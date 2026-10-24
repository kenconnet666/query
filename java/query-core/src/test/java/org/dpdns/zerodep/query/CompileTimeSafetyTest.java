package org.dpdns.zerodep.query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 泛型设计承诺这些错误写法不能编译；每次构建都用 javac 检查这个承诺。 */
@DisplayName("编译期类型检查")
class CompileTimeSafetyTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        用了作用域外的表的列         | from(Lips.class).where(l -> l.text().eq("x"));
        比较值类型和列类型不符       | from(Lips.class).where(l -> l.quantity().eq("x"));
        连接键类型不符               | from(Lips.class).join(Mara.class, (l, ma) -> ma.client().eq(l.quantity()));
        COALESCE的两个参数类型不符   | from(Lips.class).select(l -> l.quantity().coalesce(l.unit()));
        对文本列求和                 | from(Lips.class).select(l -> sum(l.unit()));
        构造器某个位置的类型不符     | from(Lips.class).select(l -> row(Delivery::new, l.quantity(), l.unit(), sum(l.quantity())));
        构造器参数个数不符           | from(Lips.class).select(l -> row(Delivery::new, l.material(), l.unit()));
        嵌套row的类型不符            | from(Lips.class).select(l -> row(PartTotal::new, l.material(), row(PartTotal.Amount::new, l.unit(), lit(1.0))));
        UNION两段的结果类型不同      | Scenarios.stock("2026").unionAll(Scenarios.lines());
        套用泛型模板的表缺少共享形状 | Scenarios.stockArm(Lips.class, "2026");
        日期列传入字符串             | from(Lips.class).where(l -> l.createdOn().between("20260101", "20260131"));
        窗口函数缺少OVER             | from(Lips.class).select(l -> rowNumber());
        CTE的getter不属于它的行类    | from(cte("x", from(Lips.class).select(l -> cols(l.material())).into(Erp.MaterialScope.class))).where(x -> x.col(Erp.Delivery::name).eq("y"));
        """)
    void 错误写法不能编译(String name, String statement) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = compile(statement);
        assertFalse(diagnostics.getDiagnostics().isEmpty(), name + " compiled, but it should not");
    }

    @Test
    void 正确写法可以编译() throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = compile(
            "from(Lips.class).join(Mara.class, (l, ma) -> ma.material().eq(l.material()))"
                + ".select((l, ma) -> row(Delivery::new, ma.partNumber(), l.unit(), sum(l.quantity())));");
        assertTrue(diagnostics.getDiagnostics().isEmpty(), diagnostics.getDiagnostics().toString());
    }

    private static DiagnosticCollector<JavaFileObject> compile(String statement) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests need a JDK, not a JRE");
        String source = """
            import org.dpdns.zerodep.query.testing.Scenarios;
            import org.dpdns.zerodep.query.testing.Erp;
            import org.dpdns.zerodep.query.testing.Erp.*;
            import static org.dpdns.zerodep.query.Sql.*;
            class Snippet { void run() { %s } }
            """.formatted(statement);
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Snippet.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        Path out = Files.createTempDirectory("snippet");
        String classpath = Path.of("target", "classes").toAbsolutePath() + File.pathSeparator + Path.of("target", "test-classes").toAbsolutePath();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        javac.getTask(null, null, diagnostics, List.of("-classpath", classpath, "-d", out.toString(), "-proc:none"), null, List.of(file)).call();
        return diagnostics;
    }
}
