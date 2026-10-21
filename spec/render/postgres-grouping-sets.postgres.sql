-- parameters: []
SELECT lips."MATNR" AS material, lips."VRKME" AS unit, COUNT(*) AS lines
FROM "QUERY_IT"."LIPS" lips
GROUP BY GROUPING SETS ((lips."MATNR", lips."VRKME"), (lips."MATNR"), ())
