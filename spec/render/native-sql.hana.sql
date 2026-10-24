-- parameters: ["EA","KG","ZOTH"]
SELECT T."material" AS "material", LPAD(MARA.MATNR, 10, '0') AS "padded", T."total" AS "total"
FROM (SELECT MATNR AS "material", SUM(LFIMG) AS "total" FROM QUERY_IT.LIPS WHERE VRKME IN (?, ?) GROUP BY MATNR) T
JOIN QUERY_IT.MARA MARA ON MARA.MATNR = T."material"
WHERE MARA.MTART <> ?
ORDER BY T."material" ASC
