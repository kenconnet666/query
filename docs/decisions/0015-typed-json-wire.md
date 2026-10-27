# 0015 线上的 typed JSON（wire）

- 状态：已采纳（2026-10-25），Java、TypeScript 与 vue-tsx-pro 的请求层三边实现。2026-10-27 起 Java 与 TypeScript 的实现在单独的仓库 [json-wire](https://github.com/kenconnet666/json-wire)（Maven `org.dpdns.zerodep:json-wire`、npm `@zerodep-dev/json-wire`），本仓库只依赖它，见第 4 节
- 起因：
  - 前后端深度配合（表格查询、表单、详情）时，Long、BigInteger、BigDecimal 写成 JSON 数字会在 JavaScript 里丢精度（`9007199254740993` 读成 `…992`，金额按 double 运算）；TS 后端的 `bigint` 根本写不进 `JSON.stringify`，decimal.js 的 `toJSON` 会用科学计数法，`CalendarDate` 写出来是一个对象。
  - 用户要求：BigDecimal、BigInteger、Integer、日期等各种类型的传输都要设计好；而且这条 JSON 流程要通用，不只给 SQL 用，非 SQL 的数据传输也能用；必要时可以依赖 Spring 自带的 Jackson，没必要就不依赖。

## 决定

### 1. 写法

JSON 能原样承载的值照常：文字、布尔、null、安全范围内的整数（Java 的 Integer、Short、Byte）、有限的浮点数。其余的值写成文字，根对象上的 `$types` 记下它们的位置和种类，对方据此还原：

| 种类 | Java | TypeScript 后端 | 前端（vue-tsx-pro） | 文字 |
| --- | --- | --- | --- | --- |
| `bigint` | Long、BigInteger | `bigint` | `bigint` | `"9007199254740993"` |
| `decimal` | BigDecimal | `Decimal` | 给了构造函数时 `Decimal`，否则十进制文字 | `"12.50"`，不用科学计数法 |
| `double` | NaN、±Infinity | `number` | `number` | `"NaN"`、`"Infinity"`、`"-Infinity"` |
| `date` | LocalDate | `CalendarDate` | ISO 文字（`dates: 'objects'` 时 `CalendarDate`） | `"2024-01-31"` |
| `time` | LocalTime | `Time` | 同上 | `"13:45:00"` |
| `timestamp` | LocalDateTime | `CalendarDateTime` | 同上 | `"2024-01-31T13:45:00.5"`：总有秒，小数去掉末尾的零 |
| `timestamptz` | OffsetDateTime、Instant、ZonedDateTime | `ZonedDateTime`、`Date` | 同上（`ZonedDateTime`） | `"2024-01-31T05:45:00Z"`：换算成 UTC |
| `bytes` | byte[] | `Uint8Array` | `Uint8Array` | 标准 base64 |

```json
{ "id": "9007199254740993", "amount": "12.5", "lines": [{ "qty": "2.75" }],
  "$types": { "id": "bigint", "amount": "decimal", "lines.qty": "decimal" } }
```

- **路径**：从根开始，用点连接属性名；数组不占一段（`lines.qty` 是每一行的 qty）；属性名里的 `~`、`.` 写成 `~0`、`~1`（同 JSON Pointer）。
- **一个路径只有一种值**：同一路径上既有 Decimal 又有文字时编码报错，否则对方无从还原。null 不占种类。
- **根不是对象**（列表、单个值）又有要还原的值时，包成 `{ "$value": 根, "$types": { "$value…": … } }`，还原时拆开。
- `$types`、`$value` 在根对象上是保留的键。
- 前端还原的默认值跟 vue-tsx-pro 的约定走：组件对外的日期是 ISO 文字，表格的数字列、NumberField 都认十进制文字。所以默认只还原 bigint、NaN 和字节，Decimal 与日期对象按选项还原。vue-tsx-pro 不在运行时导入 decimal.js，Decimal 的构造函数由应用给出（`createHttpClient({ wire: { decimal: Decimal } })`）。TS 后端一律还原成对象，因为它的列类型就是这些对象。

参照：protobuf 的 JSON 映射把 int64 写成文字、bytes 写成 base64、Timestamp 写成 RFC 3339；Google 的 `google.type.Decimal` 用文字。`$types` 是我们加的：protobuf 有 schema，HTTP 接口没有，而 TS 后端和前端要在没有 schema 的情况下还原。

### 2. 三边的实现

| | Java `org.dpdns.zerodep.wire` | TypeScript `@zerodep-dev/json-wire` | vue-tsx-pro `utils/request/wire.ts` |
| --- | --- | --- | --- |
| 编码 | `Wire.encode(value)` → Map、List、String、Number、Boolean 组成的树 | `toWire(value)` → JSON 值 | `toWire(value)` |
| 还原 | 接口的请求体一般不需要：Jackson 本来就把文字读成 BigDecimal、Long、LocalDate，Spring Boot 忽略不认识的 `$types`；自己读 typed JSON 时用 `Wire.decode(tree)` / `Wire.fromJson(text)` | `fromWire(json, { timeZone })` | `fromWire(json, { decimal, dates, timeZone })` |
| 单个值的文字 | `Wire.text(value)`、`Wire.parse(Class, text)` | `wireText(value)`、`parseWire(kind, text)` | `wireText(value)` |
| 对象取哪些属性 | record 的组件；其余取公开的 getter 和公开字段（同 Jackson 默认看到的），属性名按字段的写法 | 自身可枚举的属性，有 `toJSON` 的先调用 | 同左 |
| 自动接入 | 控制器返回 `Wire.encode(结果)` | 处理函数返回 `toWire(结果)` | `createHttpClient` 默认：请求体经 `toWire`，地址栏参数经 `wireText`，带 `$types` 的响应先 `fromWire` 再交给 `onResponse`；`wire: false` 关闭 |

- **不依赖 Jackson**：编码的结果只有 JSON 的基本结构，Jackson 2、Jackson 3、Gson 都原样写出，也不受应用的 ObjectMapper 配置（数字写法、命名策略）影响。依赖 Jackson 能少写一次 `Wire.encode`，但要同时适配 Jackson 2（Spring Boot 2.7、3）与 Jackson 3（Spring Boot 4）两套 API，还要在根上补 `$types`；收益不抵这份维护。代价是 Java 一侧不认 `@JsonIgnore`、`@JsonProperty`，要隐藏字段时另做一个返回用的 record。
- wire 与 SQL 无关，不依赖查询库，任何接口都能用；`query-core` 依赖它（表头筛选、区间的值按列类型读文字，见 0016）。TS 的核心包用它的日期、时间戳解析，没有自己的那一份。
- vue-tsx-pro 是单独的仓库，不依赖 `@zerodep-dev/json-wire`：前端的那一份按同样的写法实现。json-wire 仓库的 `spec/order.json` 是三边测试对照的同一个样例（vue-tsx-pro 里放一份副本），Java 的测试用 Jackson 写出编码结果再与它比较。

### 3. 地址栏里的值

地址栏参数没有 `$types`，按接收方期望的类型读：Spring 按 DTO 字段的类型绑定（Long、BigDecimal、ISO 日期都能读）；表头筛选、区间这类按结果列读的值由查询库用 `Wire.parse` / `parseWire` 按列类型读（0016）。写的一方用 `wireText`：Decimal 不用科学计数法，日期是 ISO，空值是空字符串。

### 4. 实现在 json-wire 仓库（2026-10-27）

wire 本来就与 SQL 无关，流程引擎（flow）等不用查询库的项目也要用，所以 Java 的 `java/wire`、TS 的 `packages/wire` 和样例 `spec/wire` 搬到单独的仓库 json-wire，查询库改为依赖它，不再各留一份、各自分叉。线上格式、扩展方式（注册值类型、换还原方式）和发布步骤以那个仓库的 `docs/` 为准。

- Java 的包名与类名不变（`org.dpdns.zerodep.wire.Wire`、`Json`），只是坐标从 `org.dpdns.zerodep:wire` 换成 `org.dpdns.zerodep:json-wire`；TS 的核心包照旧重新导出 `toWire`、`fromWire`、`wireText`、`parseWire` 等。
- 开发时依赖本机旁边的 json-wire 检出（`../json-wire`）：Maven 用它 `mvnw install` 进本机仓库的 SNAPSHOT，TS 用 `file:` 指向它的包目录。不用 `link:`：链接进来的包按自己的目录解析 `decimal.js`、`@internationalized/date`，得到另一份类，`instanceof CalendarDate` 认不出本工作区的日期；`file:` 由 pnpm 拷进工作区、按工作区解析依赖，只有一份。代价是 json-wire 重新构建后要在这里再 `pnpm install` 一次。
- 发布查询库之前 json-wire 要先发布，见 [release.md](../release.md)。
