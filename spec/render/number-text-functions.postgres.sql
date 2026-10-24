-- parameters: ["D1"]
SELECT LPAD(lips."MATNR", 5, '0') AS padded, RPAD(lips."VRKME", 4, '*') AS unit, MOD(lips."LFIMG", 2) AS remainder, POWER(lips."LFIMG", 2) AS squared, SQRT(lips."LFIMG") AS root, SIGN((lips."LFIMG" - 4)) AS sign
FROM "QUERY_IT"."LIPS" lips
WHERE lips."VBELN" = ?
