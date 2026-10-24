-- parameters: []
SELECT procinst."ID" AS id
FROM "QUERY_IT"."WF_PROCINST" procinst
LEFT JOIN (
SELECT variable."PROC_INST_ID" AS k1, COUNT(CASE WHEN variable."NAME" = 'amount' AND variable."LONG_VALUE" > 1000 THEN 1 END) AS c1, COUNT(CASE WHEN variable."NAME" = 'dept' AND variable."TEXT_VALUE" = 'X' THEN 1 END) AS c2
FROM "QUERY_IT"."WF_VARIABLE" variable
GROUP BY variable."PROC_INST_ID"
) procinst_g ON procinst_g.k1 = procinst."ID"
WHERE procinst."END_TIME" IS NULL AND COALESCE(procinst_g.c1, 0) > 0 AND COALESCE(procinst_g.c2, 0) > 0
ORDER BY procinst."ID" ASC
