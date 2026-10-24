import type { DialectClass } from "../sql/dialect.js";
import type { SourceColumn } from "../sql/expr.js";
import { renderVariant, type SqlVariant, type VariantTag } from "../sql/template.js";
import type { SqlWriter } from "../sql/writer.js";
import type { SqlType } from "../schema/types.js";
import type { rowType, Source } from "./source.js";

/** 列名到列类型的声明。 */
type ColumnTypes = Readonly<Record<string, SqlType<any>>>;

/** 列类型声明对应的行类型。 */
type RowOf<C extends ColumnTypes> = { -readonly [K in keyof C]: C[K] extends SqlType<infer V> ? V : never };

/**
 * 原生 SQL 写成的数据源，放进 FROM 或 JOIN，来自 `sqlTable({ material: text, total: decimal })\`SELECT ...\``。
 * 查询的每一列要用声明里的列名作别名并按方言加引号（可以写 `${identifier("material")}`），因为外层按这些名字引用列。
 * `.on(方言类)\`...\`` 给某个方言另一种写法。
 */
export class SqlTable<E> implements Source<E> {
  declare readonly [rowType]?: E;
  readonly entity = undefined;
  readonly filtered = false;
  readonly preferredAlias = "t";
  readonly columns: readonly SourceColumn[];
  readonly #variants: readonly SqlVariant[];

  constructor(columns: readonly SourceColumn[], variants: readonly SqlVariant[]) {
    this.columns = columns;
    this.#variants = variants;
  }

  /** 在 dialect 及其子类上改用另一段查询。 */
  on(dialect: DialectClass): VariantTag<SqlTable<E>> {
    return (parts, ...args) => new SqlTable<E>(this.columns, [...this.#variants, { dialect, parts, args }]);
  }

  /** 写出带括号的查询，别名由调用方追加。 */
  renderSource(w: SqlWriter): void {
    w.sql("(");
    renderVariant(w, this.#variants);
    w.sql(")");
  }

  /** 列名就是声明里的名字。 */
  columnName(column: SourceColumn): string {
    return column.name;
  }
}

/** 声明各列的类型，返回写原生查询的标签函数：`from(sqlTable({ material: text })\`SELECT ...\`)`。 */
export function sqlTable<C extends ColumnTypes>(columns: C): VariantTag<SqlTable<RowOf<C>>> {
  const declared = Object.entries(columns).map(([name, type]) => Object.freeze({ name, type: type as SqlType<unknown> }));
  if (declared.length === 0) throw new Error("sqlTable() needs at least one column");
  return (parts, ...args) => new SqlTable<RowOf<C>>(declared, [{ dialect: undefined, parts, args }]);
}
