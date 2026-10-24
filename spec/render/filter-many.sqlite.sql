-- parameters: ["100","ZMAT","100","EA","5"]
SELECT mara.MATNR AS material
FROM QUERY_IT.MARA AS mara
WHERE mara.MANDT = ? AND mara.MTART = ? AND EXISTS (SELECT 1
FROM QUERY_IT.LIPS AS lips
WHERE lips.MANDT = ? AND lips.MATNR = mara.MATNR AND lips.MANDT = mara.MANDT AND lips.VRKME = ? AND lips.LFIMG >= ?)
ORDER BY mara.MATNR ASC
