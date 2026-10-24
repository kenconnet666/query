-- parameters: []
SELECT lips."VBELN" AS delivery
FROM "QUERY_IT"."LIPS" lips
JOIN "QUERY_IT"."MARA" mara ON mara."MATNR" = lips."MATNR"
FOR UPDATE OF lips SKIP LOCKED
