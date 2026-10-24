-- parameters: ["2011-01-01","2025-01-01"]
SELECT event."ID" AS id, CAST(EXTRACT(YEAR FROM event."ON_DAY") AS INTEGER) AS y, CAST(EXTRACT(MONTH FROM event."AT_TIME") AS INTEGER) AS m, CAST(EXTRACT(DAY FROM event."ON_DAY") AS INTEGER) AS d, (event."ON_DAY" + 3) AS "plusDays", CAST(event."ON_DAY" + -1 * INTERVAL '1 month' AS DATE) AS "minusMonth", CAST(event."ON_DAY" + 1 * INTERVAL '1 year' AS DATE) AS "plusYear", (DATE '2024-04-30' - event."ON_DAY") AS days
FROM "QUERY_IT"."EVENTS" event
WHERE event."ON_DAY" >= ? AND event."ON_DAY" < ?
ORDER BY event."ID" ASC
