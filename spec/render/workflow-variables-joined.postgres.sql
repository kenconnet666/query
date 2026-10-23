-- parameters: ["amount","1000","dept","X"]
SELECT procinst."ID" AS id
FROM "QUERY_IT"."WF_PROCINST" procinst
JOIN "QUERY_IT"."WF_VARIABLE" variable ON variable."PROC_INST_ID" = procinst."ID" AND variable."NAME" = ? AND variable."LONG_VALUE" > ?
JOIN "QUERY_IT"."WF_VARIABLE" variable2 ON variable2."PROC_INST_ID" = procinst."ID" AND variable2."NAME" = ? AND variable2."TEXT_VALUE" = ?
WHERE procinst."END_TIME" IS NULL
ORDER BY procinst."ID" ASC
