-- parameters: ["D1","D3","D2"]
SELECT lips."MATNR" AS material
FROM "QUERY_IT"."LIPS" lips
WHERE lips."VBELN" = ?
UNION ALL
(SELECT lips2."MATNR"
FROM "QUERY_IT"."LIPS" lips2
WHERE lips2."VBELN" = ?
UNION
SELECT lips3."MATNR"
FROM "QUERY_IT"."LIPS" lips3
WHERE lips3."VBELN" = ?)
