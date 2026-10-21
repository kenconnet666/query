import { blankAsNull, column, converted, date, decimal, ignore, integer, mapFrom, table, text, timestamp, trimmed } from "@query/core";

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
}

/** MARA read the way SAP data is used: trimmed part numbers, blank makers as NULL, and the stored maker. */
@table("MARA", { schema: SCHEMA })
export class MaraView extends ClientEntity {
  @column("MATNR") material!: string;
  @column("MFRPN") @trimmed partNumber!: string;
  @column("MFRNR") @blankAsNull maker!: string | null;
  @column("MFRNR") storedMaker!: string;
}

@table("LIPS", { schema: SCHEMA })
export class Lips extends ClientEntity {
  @column("VBELN") delivery!: string;
  @column("MATNR") material!: string;
  @column("ERDAT", sapDate) createdOn!: string | null;
  @column("LFIMG", decimal) quantity!: string;
  @column("VRKME") unit!: string;
  @column("VGBEL") salesOrder!: string;
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

/** Same-named columns map automatically; a renamed one names its source. */
export class LineVO {
  delivery!: string;
  @mapFrom(Lips, "material") materialNo!: string;
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
