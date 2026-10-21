import type { Dialect, FunctionDef, FunctionRenderer } from "./dialect.js";
import { NamingStrategy } from "./metadata.js";
import type { SqlType } from "./types.js";

/** Anything that writes itself into a statement: expressions, conditions, clauses. */
export interface Renderable {
  render(w: SqlWriter): void;
}

/** A named body hoisted into the statement's leading WITH. */
export interface CteDefinition extends Renderable {
  readonly name: string;
}

/** A bound parameter: the value as written and the type that encodes it. */
export interface Binding {
  readonly value: unknown;
  readonly type: SqlType<unknown>;
}

/** Final SQL text and its parameters in placeholder order. */
export class Rendered {
  /** Database-side values, after types and converters encoded them. */
  readonly parameters: readonly unknown[];

  constructor(
    readonly sql: string,
    readonly bindings: readonly Binding[],
  ) {
    this.parameters = Object.freeze(bindings.map((b) => (b.value === null ? null : b.type.encode(b.value))));
  }

  toString(): string {
    return `${this.sql}\n-- parameters ${JSON.stringify(this.parameters, (_, v: unknown) => (typeof v === "bigint" ? v.toString() : v))}`;
  }
}

/** Settings a database instance hands to rendering. */
export interface RenderSettings {
  readonly naming?: NamingStrategy;
  readonly functions?: ReadonlyMap<FunctionDef, FunctionRenderer>;
  /** Database-wide filters; opaque to the writer, read by the query renderer. */
  readonly filters?: unknown;
}

/** Table aliases are unique per statement, across subqueries and CTE bodies, so correlation never shadows. */
class Aliases {
  readonly byOwner = new Map<object, string>();
  readonly used = new Set<string>();
}

const PARAMETER = Symbol("parameter");
type Chunk = string | typeof PARAMETER;

/**
 * Accumulates SQL text and parameters for one statement. Every node renders itself into a writer;
 * the dialect decides quoting, literals, paging, placeholders and function rendering. CTEs
 * registered while rendering are hoisted into one leading WITH by {@link statement}, dependencies
 * first, so no WITH ever appears inside a derived table. Placeholders are numbered only then,
 * because hoisting reorders the text.
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

  get naming(): NamingStrategy {
    return this.settings.naming ?? NamingStrategy.AS_IS;
  }

  /** The statement-wide alias of a source; the preferred name gets a numeric suffix when taken. */
  aliasFor(owner: object, preferred: string): string {
    const aliases = this.#aliases;
    const known = aliases.byOwner.get(owner);
    if (known !== undefined) return known;
    const base = this.dialect.foldCase(preferred);
    let alias = base;
    for (let i = 2; aliases.used.has(alias); i++) alias = base + i;
    aliases.used.add(alias);
    aliases.byOwner.set(owner, alias);
    return alias;
  }

  functionOverride(def: FunctionDef): FunctionRenderer | undefined {
    return this.settings.functions?.get(def);
  }

  sql(text: string): this {
    this.#chunks.push(text);
    return this;
  }

  identifier(name: string): this {
    return this.sql(this.dialect.quote(name));
  }

  alias(alias: string): this {
    return this.sql(this.dialect.alias(alias));
  }

  bind(value: unknown, type: SqlType<unknown>): this {
    this.#chunks.push(PARAMETER);
    this.#bindings.push({ value, type });
    return this;
  }

  literal(value: unknown, type: SqlType<unknown>): this {
    this.dialect.literal(this, value, type);
    return this;
  }

  render(node: Renderable): this {
    node.render(this);
    return this;
  }

  join(nodes: readonly Renderable[], separator: string): this {
    nodes.forEach((node, i) => {
      if (i > 0) this.sql(separator);
      node.render(this);
    });
    return this;
  }

  /** A writer with the same settings and aliases and an empty buffer, for rendering a part separately. */
  fork(): SqlWriter {
    const w = new SqlWriter(this.dialect, this.settings);
    w.#aliases = this.#aliases;
    return w;
  }

  /** Appends another writer's text and parameters, and adopts the CTEs it registered. */
  append(other: SqlWriter): this {
    this.#chunks.push(...other.#chunks);
    this.#bindings.push(...other.#bindings);
    for (const c of other.#ctes.values()) this.cte(c);
    return this;
  }

  cte(definition: CteDefinition): void {
    const known = this.#ctes.get(definition.name);
    if (known === undefined) this.#ctes.set(definition.name, definition);
    else if (known !== definition) throw new Error(`Two different CTEs are named '${definition.name}' in one statement`);
  }

  /** The finished statement: hoisted CTEs, then this writer's text. */
  statement(): Rendered {
    const order: CteDefinition[] = [];
    const bodies = new Map<CteDefinition, SqlWriter>();
    const names = new Map<string, CteDefinition>();
    for (const c of this.#ctes.values()) this.#emit(c, order, bodies, names);
    const chunks: Chunk[] = [];
    const bindings: Binding[] = [];
    order.forEach((c, i) => {
      const body = bodies.get(c)!;
      chunks.push(`${i > 0 ? ",\n" : "WITH "}${this.dialect.quote(c.name)} AS (\n`, ...body.#chunks, "\n)");
      bindings.push(...body.#bindings);
    });
    if (order.length > 0) chunks.push("\n");
    chunks.push(...this.#chunks);
    bindings.push(...this.#bindings);
    let n = 0;
    const sql = chunks.map((c) => (c === PARAMETER ? this.dialect.placeholder(++n) : c)).join("");
    return new Rendered(sql, bindings);
  }

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
