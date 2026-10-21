-- parameters: ["1","100","100"]
SELECT lips."VBELN" AS delivery, lips."MATNR" AS "materialNo", lips."ERDAT" AS "createdOn", makt."MAKTX" AS text
FROM "QUERY_IT"."LIPS" lips
LEFT JOIN "QUERY_IT"."MAKT" makt ON makt."MATNR" = lips."MATNR" AND makt."SPRAS" = ? AND makt."MANDT" = ?
WHERE lips."MANDT" = ?
ORDER BY lips."VBELN" ASC
