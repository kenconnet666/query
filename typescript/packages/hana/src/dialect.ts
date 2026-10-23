import { type CastType, type Lock, type SqlWriter, type Upsert, columnList, Dialect, doNothing } from "@zerodep-dev/query-core";

/** SAP HANA 2.0。未加引号的标识符折叠为大写。 */
export class HanaDialect extends Dialect {
  /** 方言无状态，全局共用这一个实例。 */
  static readonly instance = new HanaDialect();

  readonly name = "SAP HANA";

  /**
   * `MERGE INTO 表 AS T USING (SELECT ? AS 列, ... FROM DUMMY) AS S ON (T.键 = S.键 ...) WHEN MATCHED THEN UPDATE SET T.列 = S.列
   * WHEN NOT MATCHED THEN INSERT (列) VALUES (S.列)`；冲突了什么也不做时省略 WHEN MATCHED。值只在 USING 的 SELECT 里写一次。
   */
  override upsert(w: SqlWriter, u: Upsert): void {
    const target = w.aliasFor({}, "target");
    const source = w.aliasFor({}, "source");
    w.sql("MERGE INTO ").render(u.table).sql(" AS ").alias(target).sql("\nUSING (SELECT ");
    u.columns.forEach((c, i) => {
      if (i > 0) w.sql(", ");
      w.render(u.values[i]!).sql(" AS ").identifier(c);
    });
    w.sql(" FROM DUMMY) AS ").alias(source).sql(" ON (");
    u.keys.forEach((k, i) => {
      if (i > 0) w.sql(" AND ");
      w.alias(target).sql(".").identifier(k).sql(" = ").alias(source).sql(".").identifier(k);
    });
    w.sql(")");
    if (!doNothing(u)) {
      w.sql("\nWHEN MATCHED THEN UPDATE SET ");
      const sets = [
        ...u.updates.map((c) => (x: SqlWriter) => x.alias(target).sql(".").identifier(c).sql(" = ").alias(source).sql(".").identifier(c)),
        ...u.assignments.map(([c, v]) => (x: SqlWriter) => x.alias(target).sql(".").identifier(c).sql(" = ").render(v)),
      ];
      sets.forEach((set, i) => {
        if (i > 0) w.sql(", ");
        set(w);
      });
    }
    w.sql("\nWHEN NOT MATCHED THEN INSERT ");
    columnList(w, u.columns);
    w.sql(" VALUES (");
    u.columns.forEach((c, i) => {
      if (i > 0) w.sql(", ");
      w.alias(source).sql(".").identifier(c);
    });
    w.sql(")");
  }

  /** HANA 跳过被锁住的行写作 `IGNORE LOCKED`。 */
  override lock(w: SqlWriter, lock: Lock): void {
    if (lock === "skipLocked") w.sql("FOR UPDATE IGNORE LOCKED");
    else super.lock(w, lock);
  }

  /** 插入后在同一个连接上查这个连接最近一次生成的 IDENTITY 值；表上有会插入别的 IDENTITY 表的触发器时取到的是触发器那一次的。 */
  override get identityQuery(): string {
    return "SELECT CURRENT_IDENTITY_VALUE() FROM DUMMY";
  }

  protected constructor() {
    super();
  }

  /** 只有大写字母、数字、下划线、# 和 $ 构成的名称可以不加引号。 */
  protected isBare(identifier: string): boolean {
    return /^[A-Z_][A-Z0-9_#$]*$/.test(identifier);
  }

  /** 未加引号的标识符折叠为大写。 */
  foldCase(identifier: string): string {
    return identifier.toUpperCase();
  }

  /** HANA 没有无 FROM 的 SELECT；DUMMY 是它的单行表。 */
  override fromNothing(w: SqlWriter): void {
    w.sql("\nFROM DUMMY");
  }

  /** 613：执行因超时而中止。 */
  override isTimeout(error: unknown): boolean {
    return HanaDialect.errorProperty(error, "code") === 613;
  }

  /** 含非 ASCII 字符的文本写成 N'...'，以免内容受损。 */
  protected override stringLiteral(s: string): string {
    const quoted = `'${s.replaceAll("'", "''")}'`;
    return /^[\x00-\x7f]*$/.test(s) ? quoted : "N" + quoted;
  }

  /** 文本用 NVARCHAR、双精度用 DOUBLE，其余用通用写法。 */
  override castType(type: CastType<unknown>): string {
    if (type.kind === "text") return `NVARCHAR(${type.length})`;
    if (type.kind === "double") return "DOUBLE";
    return super.castType(type);
  }
}
