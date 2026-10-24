-- parameters: ["D1"]
SELECT LPAD(LIPS.MATNR, 5, '0') AS "padded", RPAD(LIPS.VRKME, 4, '*') AS "unit", MOD(LIPS.LFIMG, 2) AS "remainder", POWER(LIPS.LFIMG, 2) AS "squared", SQRT(LIPS.LFIMG) AS "root", SIGN((LIPS.LFIMG - 4)) AS "sign"
FROM QUERY_IT.LIPS LIPS
WHERE LIPS.VBELN = ?
