import type { Dialect, DialectClass } from "./dialect.js";
import { Cond, Expr, renderTemplate, type TemplateArg } from "./expr.js";
import type { SqlType } from "../schema/types.js";
import type { Renderable, SqlWriter } from "./writer.js";

/** 原生 SQL 的一种写法：模板片段、参数，以及适用的方言（undefined 是默认写法）。 */
export interface SqlVariant {
  readonly dialect: DialectClass | undefined;
  readonly parts: readonly string[];
  readonly args: readonly TemplateArg[];
}

/** 写出与当前方言最接近的写法：匹配的方言里继承层次最深的那个；都不匹配时是默认写法。 */
export function renderVariant(w: SqlWriter, variants: readonly SqlVariant[]): void {
  const dialect: Dialect = w.dialect;
  let best = variants[0]!;
  for (const v of variants) {
    if (v.dialect === undefined || !(dialect instanceof v.dialect)) continue;
    if (best.dialect === undefined || v.dialect.prototype instanceof best.dialect) best = v;
  }
  renderTemplate(w, best.parts, best.args);
}

/** 给原生 SQL 加一种方言写法的标签函数：`.on(MysqlDialect)\`...\``。 */
export type VariantTag<R> = (parts: TemplateStringsArray, ...args: TemplateArg[]) => R;

/**
 * 原生 SQL 写成的表达式，来自 `sql(text)\`LPAD(${l.material}, ${18}, '0')\``：表达式原位渲染，其他值绑定
 * （SELECT 列表里写成字面量），数组展开成逗号分隔的多个参数。`.on(方言类)\`...\`` 给某个方言另一种写法。
 */
export class SqlExpr<T> extends Expr<T> {
  readonly #variants: readonly SqlVariant[];

  constructor(type: SqlType<NonNullable<T>>, variants: readonly SqlVariant[]) {
    super(type);
    this.#variants = variants;
  }

  /** 在 dialect 及其子类上改用另一段模板。 */
  on(dialect: DialectClass): VariantTag<SqlExpr<T>> {
    return (parts, ...args) => new SqlExpr<T>(this.type, [...this.#variants, { dialect, parts, args }]);
  }

  /** 按当前方言写出模板。 */
  render(w: SqlWriter): void {
    renderVariant(w, this.#variants);
  }
}

/** 原生 SQL 写成的条件，来自 `sqlCond\`${l.unit} IN (${units})\``，规则同 {@link SqlExpr}。 */
export class SqlCond extends Cond {
  readonly #variants: readonly SqlVariant[];

  constructor(variants: readonly SqlVariant[]) {
    super();
    this.#variants = variants;
  }

  /** 在 dialect 及其子类上改用另一段模板。 */
  on(dialect: DialectClass): VariantTag<SqlCond> {
    return (parts, ...args) => new SqlCond([...this.#variants, { dialect, parts, args }]);
  }

  /** 按当前方言写出模板。 */
  render(w: SqlWriter): void {
    renderVariant(w, this.#variants);
  }
}

/** 按方言加引号的标识符（列名、表名、别名），放进原生 SQL，让同一段模板在三个库上都能用。 */
export function identifier(name: string): Renderable {
  if (name.trim() === "") throw new Error("identifier needs a name");
  return { render: (w) => w.identifier(name) };
}
