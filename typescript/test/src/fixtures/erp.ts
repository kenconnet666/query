import { all, blankAsNull, column, converted, date, decimal, ignore, integer, many, one, table, text, timestamp, trimmed, type Many, type One } from "@zerodep-dev/query-core";

/** SAP-style entities shared by the unit tests and the live scenarios of every dialect, as in the Java tests. */
export const SCHEMA = "QUERY_IT";

/** SAP keeps dates as NVARCHAR(8) 'yyyyMMdd'; '' and '00000000' mean no date. */
export const sapDate = converted<string, string>(text, {
  toDatabase: (day) => day.replaceAll("-", ""),
  fromDatabase: (stored) => {
    const v = stored.trim();
    return v === "" || v === "00000000" ? null : `${v.slice(0, 4)}-${v.slice(4, 6)}-${v.slice(6, 8)}`;
  },
});

// ---------- tables ----------

export abstract class ClientEntity {
  @column("MANDT") client!: string;
}

@table("MARA", { schema: SCHEMA })
export class Mara extends ClientEntity {
  @column("MATNR") material!: string;
  @column("MFRPN") partNumber!: string;
  @column("MFRNR") manufacturer!: string;
  @column("MTART") materialType!: string;

  /** 一对多：这个物料的交货行（同一集团）。 */
  @many(() => Lips, (m, l) => all(l.material.eq(m.material), l.client.eq(m.client))) deliveries!: Many<Lips>;

  /** 多对一：语言 1 的物料描述。 */
  @one(() => Makt, (m, t) => all(t.material.eq(m.material), t.client.eq(m.client), t.language.eq("1"))) text!: One<Makt>;
}

/** MARA read the way SAP data is used: trimmed part numbers, blank makers as NULL, and the stored maker. */
@table("MARA", { schema: SCHEMA })
export class MaraView extends ClientEntity {
  @column("MATNR") material!: string;
  @column("MFRPN") @trimmed partNumber!: string;
  @column("MFRNR") @blankAsNull maker!: string | null;
  @column("MFRNR") storedMaker!: string;
}

/** LIPS under a class name that is a reserved word, so the generated alias needs quotes. */
@table("LIPS", { schema: SCHEMA })
export class Order {
  @column("VBELN") delivery!: string;
  @column("MATNR") material!: string;
}

@table("LIPS", { schema: SCHEMA })
export class Lips extends ClientEntity {
  @column("VBELN") delivery!: string;
  @column("MATNR") material!: string;
  @column("ERDAT", sapDate) createdOn!: string | null;
  @column("LFIMG", decimal) quantity!: string;
  @column("VRKME") unit!: string;
  @column("VGBEL") salesOrder!: string;

  /** 多对一：物料主数据（同一集团）。 */
  @one(() => Mara, (l, m) => all(m.material.eq(l.material), m.client.eq(l.client))) mara!: One<Mara>;

  /** 多对一：语言 1 的物料描述。 */
  @one(() => Makt, (l, t) => all(t.material.eq(l.material), t.client.eq(l.client), t.language.eq("1"))) text!: One<Makt>;

  /** 一对多：参考的销售订单的业务数据。 */
  @many(() => Vbkd, (l, v) => v.salesOrder.eq(l.salesOrder)) salesData!: Many<Vbkd>;

  /** 多对一：任意语言的物料描述，只用于渲染测试。 */
  @one(() => Makt, (l, t) => all(t.client.eq(l.client), t.material.eq(l.material))) anyText!: One<Makt>;
}

@table("MAKT", { schema: SCHEMA })
export class Makt extends ClientEntity {
  @column("MATNR") material!: string;
  @column("SPRAS") language!: string;
  @column("MAKTX") text!: string;
}

@table("VBKD", { schema: SCHEMA })
export class Vbkd extends ClientEntity {
  @column("VBELN") salesOrder!: string;
}

/** The shared shape of the monthly stock history tables. */
export interface PeriodStock {
  client: string;
  material: string;
  year: string;
  period: string;
}

@table("MARDH", { schema: SCHEMA })
export class Mardh extends ClientEntity implements PeriodStock {
  @column("MATNR") material!: string;
  @column("LFGJA") year!: string;
  @column("LFMON") period!: string;
  @column("LABST", decimal) unrestricted!: string | null;
}

@table("MCHBH", { schema: SCHEMA })
export class Mchbh extends ClientEntity implements PeriodStock {
  @column("MATNR") material!: string;
  @column("LFGJA") year!: string;
  @column("LFMON") period!: string;
  @column("CLABS", decimal) batchUnrestricted!: string | null;
}

/** Real DATE and TIMESTAMP columns, for time-zone checks. */
@table("EVENTS", { schema: SCHEMA })
export class Event {
  @column("ID", integer) id!: number;
  @column("ON_DAY", date) onDay!: string;
  @column("AT_TIME", timestamp) atTime!: string;
}

// ---------- shapes and results ----------

/** A line result mapped with into(...): the projection keys match the field names. */
export class LineVO {
  delivery!: string;
  materialNo!: string;
  createdOn!: string | null;
  text!: string | null;
  @ignore computedLater?: string;
}

export class DeliveryVO {
  partNumber!: string;
  name!: string | null;
  quantity!: string | null;

  get label(): string {
    return `${this.partNumber}:${this.name}`;
  }
}
