-- parameters: []
SELECT lips."MATNR" AS material, ARRAY_AGG(lips."VBELN" ORDER BY lips."VBELN" DESC) AS deliveries
FROM "QUERY_IT"."LIPS" lips
GROUP BY lips."MATNR"
