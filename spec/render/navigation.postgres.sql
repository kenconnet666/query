-- parameters: ["100","1","100","100","MK1"]
SELECT lips."VBELN" AS delivery, mara."MFRPN" AS "partNumber", makt."MAKTX" AS text
FROM "QUERY_IT"."LIPS" lips
LEFT JOIN "QUERY_IT"."MARA" mara ON mara."MATNR" = lips."MATNR" AND mara."MANDT" = lips."MANDT" AND mara."MANDT" = ?
LEFT JOIN "QUERY_IT"."MAKT" makt ON makt."MATNR" = mara."MATNR" AND makt."MANDT" = mara."MANDT" AND makt."SPRAS" = ? AND makt."MANDT" = ?
WHERE lips."MANDT" = ? AND mara."MFRNR" = ?
ORDER BY lips."VBELN" ASC
