-- parameters: ["EA"]
SELECT lips."MATNR" AS material, SUM(lips."LFIMG") FILTER (WHERE lips."VRKME" = ?) AS each, STRING_AGG(lips."VBELN", ';' ORDER BY lips."VBELN" DESC) AS deliveries
FROM "QUERY_IT"."LIPS" lips
GROUP BY lips."MATNR"
