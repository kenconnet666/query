import { Database, Dialect, type DatabaseOptions, type Driver, type DriverResult } from "@query/core";

/** A neutral dialect for rendering tests: upper-case bare identifiers and a configurable IN limit. */
export class TestDialect extends Dialect {
  readonly name = "test";

  constructor(private readonly maxInList = 1000) {
    super();
  }

  protected isBare(identifier: string): boolean {
    return /^[A-Z_][A-Z0-9_]*$/.test(identifier);
  }

  foldCase(identifier: string): string {
    return identifier.toUpperCase();
  }

  override get maxInListSize(): number {
    return this.maxInList;
  }
}

/** A driver answering from canned rows and recording what it was asked. */
export class FakeDriver implements Driver {
  readonly statements: { sql: string; parameters: readonly unknown[] }[] = [];

  constructor(private readonly answer: (sql: string) => DriverResult = () => ({ columns: [], rows: [] })) {}

  async query(sql: string, parameters: readonly unknown[]): Promise<DriverResult> {
    this.statements.push({ sql, parameters });
    return this.answer(sql);
  }
}

/** A database instance of the test dialect; without a driver it never connects. */
export class TestDatabase extends Database {
  constructor(options: DatabaseOptions = {}, driver: Driver = new FakeDriver(() => { throw new Error("TestDatabase never connects"); })) {
    super(new TestDialect(), driver, options);
  }
}

export const rows = (columns: readonly string[], ...values: readonly (readonly unknown[])[]): DriverResult => ({ columns, rows: values });
