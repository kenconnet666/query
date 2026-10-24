-- parameters: []
SELECT lips."MATNR" AS material, SUM(CASE WHEN lips."VRKME" = 'EA' THEN lips."LFIMG" END) AS each, STRING_AGG(lips."VBELN", ';' ORDER BY lips."VBELN" DESC) AS deliveries
FROM "QUERY_IT"."LIPS" lips
GROUP BY lips."MATNR"
