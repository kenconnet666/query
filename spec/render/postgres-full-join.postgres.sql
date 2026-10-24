-- parameters: []
SELECT lips."VBELN" AS delivery, mara."MATNR" AS material
FROM "QUERY_IT"."LIPS" lips
FULL JOIN "QUERY_IT"."MARA" mara ON mara."MATNR" = lips."MATNR"
