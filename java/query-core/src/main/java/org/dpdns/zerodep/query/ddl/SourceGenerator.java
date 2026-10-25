package org.dpdns.zerodep.query.ddl;

import org.dpdns.zerodep.query.table.column.Col;
import org.dpdns.zerodep.query.table.column.ColBlob;
import org.dpdns.zerodep.query.table.column.ColClob;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把读回的表结构写成只有列的父接口的 Java 源码，供 {@code query:pull} 使用。生成的接口继承 {@code Entity}，不带 {@code @Table}；
 * 用户的表接口继承它，再写关系、过滤器和覆盖的注解。重新生成只覆盖这个文件。
 */
public final class SourceGenerator {
    /** Java 关键字和 Entity 已有的方法名，列方法不能用；用到时在后面加 {@code Column}。 */
    private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
        "instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "record", "var", "yield", "columns", "one", "many", "hashCode", "toString", "equals", "getClass");

    private SourceGenerator() { }

    /** 列名转成列方法名：{@code ORDER_ID} 写成 {@code orderId}，{@code MATNR} 写成 {@code matnr}。 */
    public static String propertyName(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_' || c == '$' || c == '#' || c == ' ') {
                upper = out.length() > 0;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = false;
        }
        String name = out.length() == 0 || !Character.isJavaIdentifierStart(out.charAt(0)) ? "c" + out : out.toString();
        return RESERVED.contains(name) ? name + "Column" : name;
    }

    /** 表名转成父接口名：{@code S_ITEM} 写成 {@code SItemColumns}。 */
    public static String typeName(String table) {
        String p = propertyName(table);
        return Character.toUpperCase(p.charAt(0)) + p.substring(1).replaceAll("Column$", "") + "Columns";
    }

    /**
     * 父接口 typeName 的源码，放在 packageName 里；columns 不为空时只生成这些列（列名，不分大小写），否则生成全部列。
     */
    public static String java(TableDef table, String packageName, String typeName, List<String> columns) {
        List<TableDef.Column> chosen = choose(table, columns);
        Set<String> imports = new TreeSet<>(List.of("org.dpdns.zerodep.query.annotation.Column", "org.dpdns.zerodep.query.table.Entity"));
        List<String> body = new ArrayList<>();
        for (TableDef.Column c : chosen) {
            boolean id = table.primaryKey().stream().anyMatch(k -> k.equalsIgnoreCase(c.name()));
            Class<?> javaType = javaType(c.type());
            Class<?> column = c.type().kind() == ColumnType.Kind.CLOB ? ColClob.class : c.type().kind() == ColumnType.Kind.BLOB ? ColBlob.class : Col.interfaceFor(javaType);
            if (!javaType.isArray() && !javaType.getPackageName().equals("java.lang")) imports.add(javaType.getName());
            imports.add(column.getName());
            if (id) imports.add("org.dpdns.zerodep.query.annotation.Id");
            if (c.identity()) imports.add("org.dpdns.zerodep.query.annotation.Generated");
            body.add("    /** " + describe(c, id) + " */");
            body.add("    " + (id ? "@Id " : "") + (c.identity() ? "@Generated " : "") + "@Column(" + columnAttributes(c, id) + ") "
                + column.getSimpleName() + "<" + javaType.getSimpleName() + "> " + propertyName(c.name()) + "();");
            body.add("");
        }
        if (!body.isEmpty()) body.remove(body.size() - 1);
        StringBuilder out = new StringBuilder();
        out.append("package ").append(packageName).append(";\n\n");
        List<String> project = imports.stream().filter(i -> !i.startsWith("java.")).toList();
        List<String> jdk = imports.stream().filter(i -> i.startsWith("java.")).toList();
        project.forEach(i -> out.append("import ").append(i).append(";\n"));
        if (!jdk.isEmpty()) {
            out.append("\n");
            jdk.forEach(i -> out.append("import ").append(i).append(";\n"));
        }
        out.append("\n/**\n * ").append(table.qualifiedName()).append(table.comment().isEmpty() ? " " : "（" + table.comment().replace("*/", "* /") + "）")
            .append("的列，由 query:pull 从数据库生成；重新生成会覆盖这个文件，不要手改。\n")
            .append(" * 在自己的表接口里继承它：{@code @Table(").append(table.schema().isEmpty() ? "" : "schema = \"" + table.schema() + "\", ")
            .append("value = \"").append(table.name()).append("\") interface X extends ").append(typeName).append(" { ... }}。\n */\n")
            .append("public interface ").append(typeName).append(" extends Entity {\n");
        body.forEach(line -> out.append(line).append("\n"));
        out.append("}\n");
        return out.toString();
    }

    /** 按白名单挑列，保持白名单的顺序；白名单里的列不存在时报错。 */
    static List<TableDef.Column> choose(TableDef table, List<String> columns) {
        if (columns == null || columns.isEmpty()) return table.columns();
        List<TableDef.Column> out = new ArrayList<>();
        for (String name : columns) {
            out.add(table.column(name).orElseThrow(() -> new IllegalArgumentException(table.qualifiedName() + " has no column " + name)));
        }
        return out;
    }

    /** {@code @Column} 的属性：列名，以及与默认值不同的长度、精度、可空、默认值和注释。 */
    private static String columnAttributes(TableDef.Column c, boolean id) {
        List<String> attributes = new ArrayList<>();
        attributes.add("value = \"" + c.name() + "\"");
        ColumnType t = c.type();
        switch (t.kind()) {
            case TEXT, BYTES -> { if (t.length() != Declarations.DEFAULT_TEXT) attributes.add("length = " + t.length()); }
            case DECIMAL -> {
                if (t.length() != Declarations.DEFAULT_PRECISION || t.scale() != Declarations.DEFAULT_SCALE) {
                    attributes.add("precision = " + t.length());
                    attributes.add("scale = " + t.scale());
                }
            }
            default -> { }
        }
        if (!c.nullable() && !id && !c.identity()) attributes.add("nullable = false");
        if (!c.defaultValue().isEmpty()) attributes.add("defaultValue = \"" + c.defaultValue().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        if (!c.comment().isEmpty()) attributes.add("comment = \"" + c.comment().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        return attributes.size() == 1 ? "\"" + c.name() + "\"" : String.join(", ", attributes);
    }

    /** 列的 Java 类型（基本类型用包装类）；列接口按它选，见 {@link Col#interfaceFor}。 */
    private static Class<?> javaType(ColumnType t) {
        return switch (t.kind()) {
            case TEXT, CLOB -> String.class;
            case DECIMAL -> BigDecimal.class;
            case SMALLINT -> Short.class;
            case INTEGER -> Integer.class;
            case BIGINT -> Long.class;
            case DOUBLE -> Double.class;
            case BOOLEAN -> Boolean.class;
            case DATE -> LocalDate.class;
            case TIMESTAMP -> LocalDateTime.class;
            case BYTES, BLOB -> byte[].class;
        };
    }

    /** 列方法的文档注释：列名、类型、约束和库里的注释。 */
    private static String describe(TableDef.Column c, boolean id) {
        StringBuilder out = new StringBuilder(c.name()).append("：").append(c.type());
        if (id) out.append("，主键");
        if (c.identity()) out.append("，数据库生成");
        else if (!c.nullable() && !id) out.append("，NOT NULL");
        if (!c.comment().isEmpty()) out.append("。").append(c.comment().replace("*/", "* /"));
        return out.toString();
    }
}
