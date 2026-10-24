import { MysqlDatabase } from "@zerodep-dev/query-mysql";
import { liveScenarios } from "./live.js";
import { mysqlScenarios, mysqlTarget } from "./mysql-scenarios.js";

liveScenarios(
  mysqlTarget("MYSQL", (driver, options) => new MysqlDatabase(driver, options)),
  mysqlScenarios,
);
