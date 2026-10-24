-- parameters: ["2026-01-15","2026-03-01"]
SELECT shevent."NOTE" AS note, SUM(shevent."AMOUNT") AS total
FROM (SELECT "AMOUNT", "DAY", "ID", "NOTE" FROM "QUERY_IT"."SH_EVENT_202601" UNION ALL SELECT "AMOUNT", "DAY", "ID", "NOTE" FROM "QUERY_IT"."SH_EVENT_202602") shevent
WHERE shevent."DAY" >= ? AND shevent."DAY" < ?
GROUP BY shevent."NOTE"
