import type { Database } from "../exec/database.js";
import { describeType, findColumn, qualifiedName, type ColumnDef, type ColumnType, type TableDef } from "./model.js";

/**
 * `query-pull`：把读回的表结构写成只有列的基类，与 Java 的 `query:pull` 对应。生成的类不带 `@table`；
 * 用户的表类继承它，再写关系、过滤器和覆盖的装饰器。重新生成只覆盖这个文件。
 */

/** Java 关键字和 Java 的 Entity 已有的方法名；两端的属性名一致，所以 TS 也避开它们，用到时在后面加 `Column`。 */
const RESERVED = new Set(
  ("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if " +
    "implements import instanceof int interface long native new package private protected public return short static strictfp super switch " +
    "synchronized this throw throws transient try void volatile while true false null record var yield columns one many hashCode toString equals getClass").split(" "),
);

/** 列名转成属性名：`ORDER_ID` 写成 `orderId`，`MATNR` 写成 `matnr`；与 Java 的 `SourceGenerator.propertyName` 相同。 */
export function propertyName(column: string): string {
  let out = "";
  let upper = false;
  for (const c of column) {
    if (c === "_" || c === "$" || c === "#" || c === " ") {
      upper = out.length > 0;
      continue;
    }
    out += upper ? c.toUpperCase() : c.toLowerCase();
    upper = false;
  }
  const name = out === "" || !/^[\p{L}_$]/u.test(out) ? "c" + out : out;
  return RESERVED.has(name) ? name + "Column" : name;
}

/** 表名转成基类名：`S_ITEM` 写成 `SItemColumns`。 */
export function generatedTypeName(table: string): string {
  const p = propertyName(table);
  return p.charAt(0).toUpperCase() + p.slice(1).replace(/Column$/, "") + "Columns";
}

/** 值类型来自哪个包：模块名到它导出的类型名。 */
const VALUE_MODULES: Readonly<Record<string, readonly string[]>> = {
  "@internationalized/date": ["CalendarDate", "CalendarDateTime"],
  "decimal.js": ["Decimal"],
};

/** 列的 SQL 类型常量和字段的 TypeScript 类型。 */
function tsType(t: ColumnType): [string, string] {
  switch (t.kind) {
    case "TEXT":
    case "CLOB":
      return ["text", "string"];
    case "DECIMAL":
      return ["decimal", "Decimal"];
    case "SMALLINT":
      return ["smallint", "number"];
    case "INTEGER":
      return ["integer", "number"];
    case "BIGINT":
      return ["bigint", "bigint"];
    case "DOUBLE":
      return ["double", "number"];
    case "BOOLEAN":
      return ["boolean", "boolean"];
    case "DATE":
      return ["date", "CalendarDate"];
    case "TIMESTAMP":
      return ["timestamp", "CalendarDateTime"];
    case "BYTES":
    case "BLOB":
      return ["bytes", "Uint8Array"];
  }
}

/** `@column` 第三个参数里与缺省不同的选项。 */
function columnOptions(c: ColumnDef, id: boolean): string {
  const o: string[] = [];
  const t = c.type;
  if ((t.kind === "TEXT" || t.kind === "BYTES") && t.length !== 255) o.push(`length: ${t.length}`);
  if (t.kind === "DECIMAL" && (t.length !== 18 || t.scale !== 2)) o.push(`precision: ${t.length}`, `scale: ${t.scale}`);
  if (t.kind === "CLOB" || t.kind === "BLOB") o.push("lob: true");
  if (!c.nullable && !id && !c.identity) o.push("nullable: false");
  if (c.defaultValue !== "") o.push(`default: ${JSON.stringify(c.defaultValue)}`);
  if (c.comment !== "") o.push(`comment: ${JSON.stringify(c.comment)}`);
  return o.length === 0 ? "" : `, { ${o.join(", ")} }`;
}

/** 属性的文档注释：列名、类型、约束和库里的注释，与 Java 的写法相同。 */
function describe(c: ColumnDef, id: boolean): string {
  let out = `${c.name}：${describeType(c.type)}`;
  if (id) out += "，主键";
  if (c.identity) out += "，数据库生成";
  else if (!c.nullable && !id) out += "，NOT NULL";
  if (c.comment !== "") out += "。" + c.comment.replaceAll("*/", "* /");
  return out;
}

/** 按白名单挑列，保持白名单的顺序；白名单里的列不存在时报错。 */
function choose(table: TableDef, columns: readonly string[]): readonly ColumnDef[] {
  if (columns.length === 0) return table.columns;
  return columns.map((name) => findColumn(table, name) ?? fail(`${qualifiedName(table)} has no column ${name}`));
}

function fail(message: string): never {
  throw new Error(message);
}

/**
 * 基类 typeName 的源码；columns 不为空时只生成这些列（列名，不分大小写），否则生成全部列。
 * 装饰器从 `@zerodep-dev/query-core` 导入，各方言包都依赖它；小数、日期的值类型从它的 peerDependencies 导入。
 */
export function typescriptSource(table: TableDef, typeName: string, columns: readonly string[] = []): string {
  const imports = new Set(["column"]);
  const values = new Set<string>();
  const body: string[] = [];
  for (const c of choose(table, columns)) {
    const id = table.primaryKey.some((k) => k.toLowerCase() === c.name.toLowerCase());
    const [sqlType, valueType] = tsType(c.type);
    imports.add(sqlType);
    if (Object.values(VALUE_MODULES).some((names) => names.includes(valueType))) values.add(valueType);
    if (id) imports.add("id");
    if (c.identity) imports.add("generated");
    const nullable = c.nullable && !id && !c.identity;
    body.push(`  /** ${describe(c, id)} */`);
    body.push(`  ${id ? "@id " : ""}${c.identity ? "@generated " : ""}@column(${JSON.stringify(c.name)}, ${sqlType}${columnOptions(c, id)}) ${propertyName(c.name)}!: ${valueType}${nullable ? " | null" : ""};`);
    body.push("");
  }
  body.pop();
  const comment = table.comment === "" ? " " : `（${table.comment.replaceAll("*/", "* /")}）`;
  const schema = table.schema === "" ? "" : `, { schema: ${JSON.stringify(table.schema)} }`;
  const valueImports = Object.entries(VALUE_MODULES)
    .map(([module, names]) => [module, names.filter((n) => values.has(n))] as const)
    .filter(([, names]) => names.length > 0)
    .map(([module, names]) => `import type { ${names.join(", ")} } from "${module}";\n`)
    .sort()
    .join("");
  return (
    valueImports +
    `import { ${[...imports].sort().join(", ")} } from "@zerodep-dev/query-core";\n\n` +
    `/**\n * ${qualifiedName(table)}${comment}的列，由 query-pull 从数据库生成；重新生成会覆盖这个文件，不要手改。\n` +
    ` * 在自己的表类里继承它：\`@table(${JSON.stringify(table.name)}${schema}) export class X extends ${typeName} { ... }\`。\n */\n` +
    `export class ${typeName} {\n${body.map((l) => l + "\n").join("")}}\n`
  );
}

/** 一张要生成的表：typeName 缺省由表名推导，columns 缺省为全部列。 */
export interface PullTable {
  readonly name: string;
  readonly typeName?: string;
  readonly columns?: readonly string[];
}

/** 生成的一个文件：相对 outDir 的文件名和内容。 */
export interface PulledFile {
  readonly file: string;
  readonly source: string;
}

/** 从 db 读出 schema 里的这些表（only 不为空时只取其中列出的表名，不分大小写），生成源码。 */
export async function pullTables(db: Database, schema: string, tables: readonly PullTable[], only: readonly string[] = []): Promise<PulledFile[]> {
  const wanted = (name: string) => only.length === 0 || only.some((o) => o.toLowerCase() === name.toLowerCase());
  const out: PulledFile[] = [];
  for (const t of tables.filter((t) => wanted(t.name))) {
    const table = (await db.schema().read(schema, t.name)) ?? fail(`Table ${t.name} does not exist in schema ${schema === "" ? "(current)" : schema}`);
    const typeName = t.typeName ?? generatedTypeName(t.name);
    out.push({ file: `${typeName}.ts`, source: typescriptSource(table, typeName, t.columns ?? []) });
  }
  return out;
}

/** `query-pull-*` 的配置文件（缺省 `query-pull.json`）。 */
export interface PullConfig {
  /** 交给驱动的连接参数，各方言包的说明里列出写法；密码不要写在这里。 */
  readonly connection: Record<string, unknown>;
  /** 存放密码的环境变量名；bin 把它的值放进 connection 里驱动认的密码字段。 */
  readonly passwordEnv?: string;
  /** 表所在的 schema；缺省为连接当前的 schema。 */
  readonly schema?: string;
  /** 生成文件的目录，缺省 `src/generated`。 */
  readonly outDir?: string;
  readonly tables: readonly PullTable[];
}

/** 输出到控制台；核心包不依赖 DOM 或 Node 的类型声明。 */
function print(text: string): void {
  (globalThis as { console?: { log(text: string): void } }).console?.log(text);
}

/** Node 的 fs 中用到的部分；核心包不依赖 Node 的类型。 */
interface NodeFs {
  readFileSync(path: string, encoding: "utf8"): string;
  writeFileSync(path: string, data: string, encoding: "utf8"): void;
  existsSync(path: string): boolean;
  mkdirSync(path: string, options: { recursive: true }): void;
}

/** Node 的 process 中用到的部分。 */
interface NodeProcess {
  readonly argv: readonly string[];
  readonly env: Record<string, string | undefined>;
  getBuiltinModule(id: string): unknown;
}

/**
 * 方言包 bin 的主体：读配置文件（命令行第一个参数，缺省 `query-pull.json`），用 connect 建库连接，生成后只写内容有变化的文件。
 * `--only=LIPS,MARA` 只重新生成列出的表。connect 拿到的连接参数里已经放好了密码（passwordField 指定字段名）。
 */
export async function runPull(passwordField: string, connect: (connection: Record<string, unknown>) => Promise<{ db: Database; close(): Promise<void> }>): Promise<void> {
  const proc = (globalThis as { process?: NodeProcess }).process ?? fail("query-pull runs on Node");
  const fs = proc.getBuiltinModule("node:fs") as NodeFs;
  const args = proc.argv.slice(2);
  const only = args.find((a) => a.startsWith("--only="))?.slice("--only=".length).split(",").map((s) => s.trim()).filter((s) => s !== "") ?? [];
  const configPath = args.find((a) => !a.startsWith("--")) ?? "query-pull.json";
  const config = JSON.parse(fs.readFileSync(configPath, "utf8")) as PullConfig;
  const connection = { ...config.connection };
  if (config.passwordEnv !== undefined) connection[passwordField] = proc.env[config.passwordEnv] ?? fail(`Environment variable ${config.passwordEnv} is not set`);
  const outDir = config.outDir ?? "src/generated";
  const { db, close } = await connect(connection);
  try {
    const files = await pullTables(db, config.schema ?? "", config.tables, only);
    fs.mkdirSync(outDir, { recursive: true });
    let written = 0;
    for (const f of files) {
      const path = `${outDir}/${f.file}`;
      if (fs.existsSync(path) && fs.readFileSync(path, "utf8") === f.source) continue;
      fs.writeFileSync(path, f.source, "utf8");
      written++;
      print(`  ${path}`);
    }
    print(`query-pull wrote ${written} file(s) under ${outDir}`);
  } finally {
    await close();
  }
}
