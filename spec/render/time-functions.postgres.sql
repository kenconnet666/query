-- parameters: []
SELECT event."ID" AS id, CAST(EXTRACT(HOUR FROM event."AT_TIME") AS INTEGER) AS h, CAST(EXTRACT(MINUTE FROM event."AT_TIME") AS INTEGER) AS mi, CAST(FLOOR(EXTRACT(SECOND FROM event."AT_TIME")) AS INTEGER) AS s, (event."AT_TIME" + 90000 * INTERVAL '1 second') AS "plusHours", (event."AT_TIME" + -1860 * INTERVAL '1 second') AS "minusMinutes", (event."AT_TIME" + 59 * INTERVAL '1 second') AS "plusSeconds", CAST(DATE_TRUNC('month', event."ON_DAY") AS DATE) AS "monthStart"
FROM "QUERY_IT"."EVENTS" event
ORDER BY event."ID" ASC
