package org.dpdns.zerodep.query.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code mvn query:pull}：连库读出配置的表，在 {@code src/generated/java} 里写出只有列的父接口；用户的表接口继承它。
 * <pre>{@code
 * <plugin>
 *   <groupId>org.dpdns.zerodep</groupId>
 *   <artifactId>query-maven-plugin</artifactId>
 *   <version>...</version>
 *   <configuration>
 *     <dialect>hana</dialect>
 *     <url>jdbc:sap://host:39041</url>
 *     <user>${env.HANA_USER}</user>
 *     <password>${env.HANA_PASSWORD}</password>
 *     <schema>SAPHANADB</schema>
 *     <packageName>com.example.erp.generated</packageName>
 *     <tables>
 *       <table><name>LIPS</name><columns>VBELN,MATNR,LFIMG</columns></table>
 *     </tables>
 *   </configuration>
 * </plugin>
 * }</pre>
 * 只重新生成某几张表：{@code mvn query:pull -Dquery.only=LIPS,MARA}。生成目录会加进编译的源码根目录，生成的文件应提交进版本库。
 */
@Mojo(name = "pull", threadSafe = true)
public class PullMojo extends AbstractMojo {
    /** hana、postgres、mysql 或 sqlite（SQLite 的 schema 是 ATTACH 的库名，主库写 main）。 */
    @Parameter(property = "query.dialect", required = true)
    private String dialect;

    /** JDBC URL。 */
    @Parameter(property = "query.url", required = true)
    private String url;

    /** 用户。 */
    @Parameter(property = "query.user", required = true)
    private String user;

    /** 密码，用 {@code ${env.X}} 引用环境变量。 */
    @Parameter(property = "query.password")
    private String password;

    /** 表所在的 schema；不写表示连接当前的 schema。 */
    @Parameter(property = "query.schema")
    private String schema;

    /** 生成的接口所在的包。 */
    @Parameter(property = "query.package", required = true)
    private String packageName;

    /** 源码根目录。 */
    @Parameter(property = "query.output", defaultValue = "${project.basedir}/src/generated/java")
    private File outputDirectory;

    /** 要生成的表：{@code <table><name>LIPS</name><typeName>LipsColumns</typeName><columns>VBELN,MATNR</columns></table>}。 */
    @Parameter(required = true)
    private List<TableSpec> tables;

    /** 只重新生成这些表，逗号分隔；不写时生成全部。 */
    @Parameter(property = "query.only")
    private String only;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    /** pom 里的一张表。 */
    public static class TableSpec {
        /** 表名。 */
        private String name;

        /** 生成的接口名，可省略。 */
        private String typeName;

        /** 只生成这些列，逗号分隔，可省略。 */
        private String columns;
    }

    @Override public void execute() throws MojoExecutionException {
        List<Puller.Table> list = new ArrayList<>();
        for (TableSpec t : tables) {
            List<String> cols = t.columns == null || t.columns.isBlank() ? List.of() : Arrays.stream(t.columns.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            list.add(new Puller.Table(t.name, t.typeName, cols));
        }
        Set<String> onlyTables = only == null ? Set.of() : Arrays.stream(only.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        Path output = outputDirectory.toPath();
        try {
            List<Path> written = Puller.pull(new Puller.Config(dialect, url, user, password, schema, packageName, output, list, onlyTables));
            getLog().info("query:pull wrote " + written.size() + " file(s) under " + output);
            for (Path p : written) getLog().info("  " + output.relativize(p));
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("query:pull failed: " + e.getMessage(), e);
        }
        if (project != null) project.addCompileSourceRoot(output.toString());
    }
}
