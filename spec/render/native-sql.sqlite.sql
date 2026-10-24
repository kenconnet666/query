-- parameters: ["EA","KG","ZOTH"]
SELECT t.material AS material, LPAD(mara.MATNR, 10, '0') AS padded, t.total AS total
FROM (SELECT MATNR AS material, SUM(LFIMG) AS total FROM QUERY_IT.LIPS WHERE VRKME IN (?, ?) GROUP BY MATNR) AS t
JOIN QUERY_IT.MARA AS mara ON mara.MATNR = t.material
WHERE mara.MTART <> ?
ORDER BY t.material ASC
