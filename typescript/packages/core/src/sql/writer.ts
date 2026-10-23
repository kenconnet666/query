import type { Dialect, FunctionDef, FunctionRenderer } from "./dialect.js";
import { NamingStrategy } from "../schema/metadata.js";
import type { SqlType } from "../schema/types.js";

/** 能把自己写进语句的对象：表达式、条件、子句。 */
export interface Renderable {
  /** 把自己写入 SQL 写入器。 */
  render(w: SqlWriter): void;
}

/** 提升到语句开头 WITH 里的命名主体。 */
export interface CteDefinition extends Renderable {
  readonly name: string;
}

/** 绑定参数：原值以及负责编码它的类型。 */
export interface Binding {
  readonly value: unknown;
  readonly type: SqlType<unknown>;
}

/**
 * 最终的 SQL 文本，以及按占位符顺序排列的参数。打印时用 {@link toString}（SQL 加一行带类型的参数），
 * 排查问题时用 {@link debugSql}（参数写进 SQL，可以直接粘到数据库客户端执行）。
 */
export class Rendered {
  /** 数据库侧的值，已由类型和转换器编码。 */
  readonly parameters: readonly unknown[];

  constructor(
    readonly sql: string,
    readonly bindings: readonly Binding[],
    readonly dialect: Dialect,
  ) {
    this.parameters = Object.freeze(bindings.map((b) => (b.value === null ? null : b.type.encode(b.value))));
  }

  /** 带类型的参数文本，形如 `M1(text), 20260115(text), 3(integer)`；值是编码后、实际发给数据库的值。 */
  parameterText(): string {
    return this.parameters.map((v, i) => (v === null ? "null" : `${String(v)}(${this.bindings[i]!.type.name})`)).join(", ");
  }

  /**
   * 调试用的 SQL：把每个占位符换成参数的字面量，可以直接粘到数据库客户端里执行。写不成字面量的参数
   * （例如字节）保留占位符。只用于排查问题；程序执行语句时仍然绑定参数。
   */
  debugSql(): string {
    let out = "";
    let next = 0;
    for (let i = 0; i < this.sql.length; i++) {
      const quoted = this.dialect.quotedEnd(this.sql, i);
      if (quoted >= 0) {
        out += this.sql.slice(i, quoted + 1);
        i = quoted;
        continue;
      }
      const marker = next < this.bindings.length ? this.dialect.placeholder(next + 1) : undefined;
      // PostgreSQL 的 $1 不能匹配到 $10 的开头
      if (marker !== undefined && this.sql.startsWith(marker, i) && !/\d/.test(this.sql[i + marker.length] ?? "")) {
        const b = this.bindings[next++]!;
        if (this.dialect.hasLiteral(b.value, b.type)) {
          const w = new SqlWriter(this.dialect);
          this.dialect.literal(w, b.value, b.type);
          out += w.statement().sql;
        } else {
          out += marker;
        }
        i += marker.length - 1;
        continue;
      }
      out += this.sql[i];
    }
    return out;
  }

  /** SQL 后附一行带类型的参数，便于打印和对照。 */
  toString(): string {
    return this.bindings.length === 0 ? this.sql : `${this.sql}\n-- parameters: ${this.parameterText()}`;
  }
}

/** 数据库实例交给渲染过程的设置。 */
export interface RenderSettings {
  readonly naming?: NamingStrategy;
  readonly functions?: ReadonlyMap<FunctionDef, FunctionRenderer>;
  /** 数据库级的过滤器；写入器不解读，由查询渲染器读取。 */
  readonly filters?: unknown;
}

/** 表别名在一条语句内唯一，跨子查询和 CTE 主体也一样，所以关联引用不会被遮蔽。 */
class Aliases {
  readonly byOwner = new Map<object, string>();
  readonly used = new Set<string>();
  /** 探查用的写入器记下渲染过程中引用到的数据源；普通写入器为 undefined。 */
  touched: Set<object> | undefined;
}

/** 文本块中参数占位符的标记；编号要到 {@link SqlWriter.statement} 时才定。 */
const PARAMETER = Symbol("parameter");
type Chunk = string | typeof PARAMETER;

/**
 * 累积一条语句的 SQL 文本和参数。每个节点把自己渲染进写入器；
 * 引用、字面量、分页、占位符和函数的写法由方言决定。渲染期间登记的 CTE 由 {@link statement}
 * 提升到语句开头的同一个 WITH 里，被依赖的在前，所以 WITH 不会出现在派生表内部。
 * 占位符到那时才编号，因为提升会改变文本顺序。
 */
export class SqlWriter {
  readonly #ctes = new Map<string, CteDefinition>();
  readonly #chunks: Chunk[] = [];
  readonly #bindings: Binding[] = [];
  #aliases = new Aliases();

  constructor(
    readonly dialect: Dialect,
    readonly settings: RenderSettings = {},
  ) {}

  /** 本次渲染的列命名策略，默认原样。 */
  get naming(): NamingStrategy {
    return this.settings.naming ?? NamingStrategy.AS_IS;
  }

  /** 来源在整条语句中的别名；首选名已被占用时加数字后缀。 */
  aliasFor(owner: object, preferred: string): string {
    const aliases = this.#aliases;
    aliases.touched?.add(owner);
    const known = aliases.byOwner.get(owner);
    if (known !== undefined) return known;
    const base = this.dialect.foldCase(preferred);
    let alias = base;
    for (let i = 2; aliases.used.has(alias); i++) alias = base + i;
    aliases.used.add(alias);
    aliases.byOwner.set(owner, alias);
    return alias;
  }

  /** 数据库实例对该函数的改写渲染，没有则为 undefined。 */
  functionOverride(def: FunctionDef): FunctionRenderer | undefined {
    return this.settings.functions?.get(def);
  }

  /** 追加原样的 SQL 文本。 */
  sql(text: string): this {
    this.#chunks.push(text);
    return this;
  }

  /** 追加按方言加引号的标识符。 */
  identifier(name: string): this {
    return this.sql(this.dialect.quote(name));
  }

  /** 追加按方言处理过的别名。 */
  alias(alias: string): this {
    return this.sql(this.dialect.alias(alias));
  }

  /** 追加一个绑定参数的占位符。 */
  bind(value: unknown, type: SqlType<unknown>): this {
    this.#chunks.push(PARAMETER);
    this.#bindings.push({ value, type });
    return this;
  }

  /** 追加值的字面量，由方言决定写法。 */
  literal(value: unknown, type: SqlType<unknown>): this {
    this.dialect.literal(this, value, type);
    return this;
  }

  #inlining = false;

  /**
   * 常量是否渲染成字面量而不是参数。每个 SELECT 列表都会开启它，因为 HANA 无法推断那里参数的类型。
   * 带分组和 DISTINCT 的 SELECT 在 GROUP BY、HAVING、ORDER BY 里也会开启：HANA、PostgreSQL 和
   * MySQL 都拒绝 `GROUP BY SUBSTRING(x, ?, ?)`，因为两个占位符永远不算同一个表达式。
   * WHERE 和 ON 仍用参数。
   */
  get inlining(): boolean {
    return this.#inlining;
  }

  /** 设置 {@link inlining} 并返回原来的值，方便调用方还原。 */
  inline(on: boolean): boolean {
    const previous = this.#inlining;
    this.#inlining = on;
    return previous;
  }

  /** 渲染每个节点前先经过它，可以把节点换成别的写法；没有时原样渲染。 */
  #substitution: ((node: Renderable) => Renderable) | undefined;

  /** 渲染一个节点；设置了替换时先经过替换。 */
  render(node: Renderable): this {
    (this.#substitution?.(node) ?? node).render(this);
    return this;
  }

  /**
   * 设置节点替换并返回原来的设置（用完要还原）；渲染器用它把 QUALIFY 条件里的窗口函数换成派生表的列。
   * 替换只看节点本身，被换掉的节点不会再往下渲染。
   */
  substitute(next: ((node: Renderable) => Renderable) | undefined): ((node: Renderable) => Renderable) | undefined {
    const previous = this.#substitution;
    this.#substitution = next;
    return previous;
  }

  /** 用分隔符依次渲染多个节点。 */
  join(nodes: readonly Renderable[], separator: string): this {
    nodes.forEach((node, i) => {
      if (i > 0) this.sql(separator);
      this.render(node);
    });
    return this;
  }

  /**
   * 探查用的写入器：设置与本写入器相同，别名独立，并记下渲染过程中引用到的每个数据源，见 {@link touched}。
   * 渲染器用它先试渲染一遍，找出哪些隐式连接真正被用到。
   */
  probe(): SqlWriter {
    const w = new SqlWriter(this.dialect, this.settings);
    w.#aliases.touched = new Set();
    return w;
  }

  /** 探查写入器记下的数据源；普通写入器返回空集合。 */
  get touched(): ReadonlySet<object> {
    return this.#aliases.touched ?? new Set();
  }

  /** 目前写出的文本，参数写成 ?；渲染器用它比较两个表达式的写法。 */
  get text(): string {
    return this.#chunks.map((c) => (c === PARAMETER ? "?" : c)).join("");
  }

  /** 设置和别名相同、缓冲区为空的新写入器，用于单独渲染某一部分。 */
  fork(): SqlWriter {
    const w = new SqlWriter(this.dialect, this.settings);
    w.#aliases = this.#aliases;
    w.#substitution = this.#substitution;
    return w;
  }

  /** 追加另一个写入器的文本和参数，并接收它登记的 CTE。 */
  append(other: SqlWriter): this {
    pushAll(this.#chunks, other.#chunks);
    pushAll(this.#bindings, other.#bindings);
    for (const c of other.#ctes.values()) this.cte(c);
    return this;
  }

  /** 登记要提升到开头 WITH 的 CTE；同名但不是同一个定义则报错。 */
  cte(definition: CteDefinition): void {
    const known = this.#ctes.get(definition.name);
    if (known === undefined) this.#ctes.set(definition.name, definition);
    else if (known !== definition) throw new Error(`Two different CTEs are named '${definition.name}' in one statement`);
  }

  /** 完成的语句：先是提升的 CTE，再是本写入器的文本。 */
  statement(): Rendered {
    const order: CteDefinition[] = [];
    const bodies = new Map<CteDefinition, SqlWriter>();
    const names = new Map<string, CteDefinition>();
    for (const c of this.#ctes.values()) this.#emit(c, order, bodies, names);
    const chunks: Chunk[] = [];
    const bindings: Binding[] = [];
    order.forEach((c, i) => {
      const body = bodies.get(c)!;
      chunks.push(`${i > 0 ? ",\n" : "WITH "}${this.dialect.quote(c.name)} AS (\n`);
      pushAll(chunks, body.#chunks);
      chunks.push("\n)");
      pushAll(bindings, body.#bindings);
    });
    if (order.length > 0) chunks.push("\n");
    pushAll(chunks, this.#chunks);
    pushAll(bindings, this.#bindings);
    let n = 0;
    const sql = chunks.map((c) => (c === PARAMETER ? this.dialect.placeholder(++n) : c)).join("");
    return new Rendered(sql, bindings, this.dialect);
  }

  /** 渲染 CTE 主体，并先排入它依赖的 CTE，使 `order` 里被依赖的在前。 */
  #emit(c: CteDefinition, order: CteDefinition[], bodies: Map<CteDefinition, SqlWriter>, names: Map<string, CteDefinition>): void {
    if (bodies.has(c)) return;
    const clash = names.get(c.name);
    if (clash !== undefined && clash !== c) throw new Error(`Two different CTEs are named '${c.name}' in one statement`);
    names.set(c.name, c);
    const body = this.fork();
    bodies.set(c, body);
    c.render(body);
    for (const dependency of body.#ctes.values()) this.#emit(dependency, order, bodies, names);
    order.push(c);
  }
}

/** 逐项追加数组：`push(...items)` 把每项都当作实参，遇到很长的 IN 列表会栈溢出。 */
function pushAll<T>(target: T[], items: readonly T[]): void {
  for (const item of items) target.push(item);
}
