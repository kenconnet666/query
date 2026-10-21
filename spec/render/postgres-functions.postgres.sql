-- parameters: ["e%","M9"]
SELECT DISTINCT ON (lips."MATNR") lips."MATNR" AS material, lips."VBELN" AS delivery
FROM "QUERY_IT"."LIPS" lips
WHERE lips."VRKME" ILIKE ? AND lips."MATNR" IS DISTINCT FROM ? AND lips."VRKME" IS NOT DISTINCT FROM lips."VRKME"
ORDER BY lips."MATNR" ASC, lips."VBELN" DESC
