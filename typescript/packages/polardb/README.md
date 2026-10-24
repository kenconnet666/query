# @zerodep-dev/query-polardb

PolarDB for MySQL 方言包：MySQL 8 方言的全部能力（一次导入），加上闪回查询 `fromAsOf` 和热点行的库存提示 `inventory`。依赖 `@zerodep-dev/query-core` 与 `@zerodep-dev/query-mysql`，驱动同 MySQL 方言用 `mysql2`。

```bash
npm install @zerodep-dev/query-polardb mysql2
```

用法见仓库文档：[docs/api-typescript.md](https://github.com/kenconnet666/query/blob/master/docs/api-typescript.md)。Java 版与 TypeScript 版共用同一套渲染金样例，生成的 SQL 与参数完全一致。

许可证：Apache-2.0。
