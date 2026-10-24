-- parameters: ["2026-02-10","a%"]
SELECT shevent."ID" AS id
FROM "QUERY_IT"."SH_EVENT_202602" shevent
WHERE shevent."DAY" = ? AND shevent."NOTE" LIKE ?
