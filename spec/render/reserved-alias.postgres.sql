-- parameters: ["M1"]
SELECT "order"."VBELN" AS delivery
FROM "QUERY_IT"."LIPS" "order"
WHERE "order"."MATNR" = ?
ORDER BY "order"."VBELN" ASC
