/**
 * 线上的 typed JSON：bigint、Decimal、日历日期、时间、字节等 JSON 不能原样承载的值写成文字，根上的 `$types`
 * 记下它们的位置，对方据此还原。与 SQL 无关，任何接口的请求体、响应、消息都可以用；Java 的 `org.dpdns.zerodep.wire`、
 * vue-tsx-pro 的请求层用同一套写法（docs/decisions/0015）。
 *
 *   const body = JSON.stringify(toWire({ amount: new Decimal("12.50"), on: new CalendarDate(2024, 1, 31) }));
 *   const value = fromWire<Order>(JSON.parse(body));   // amount 又是 Decimal，on 又是 CalendarDate
 */
export { fromWire, toWire, TYPES, VALUE, type Json, type WireTypes } from "./json.js";
export {
  decodeBase64,
  encodeBase64,
  parseDate,
  parseTime,
  parseTimestamp,
  parseWire,
  wireText,
  wireTypeOf,
  type WireKind,
  type WireOptions,
  type WireType,
} from "./scalar.js";
