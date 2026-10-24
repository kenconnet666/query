-- parameters: ["100","ZMAT","MK1","100","1","100","20260101","20260630","100"]
WITH material_scope AS (
SELECT DISTINCT mara.MANDT AS client, mara.MATNR AS material, CASE WHEN mara.MFRPN <> TRIM(mara.MFRPN) THEN TRIM(mara.MFRPN) ELSE mara.MFRPN END AS partNumber
FROM QUERY_IT.MARA AS mara
WHERE mara.MANDT = ? AND mara.MTART = ? AND mara.MFRNR = ?
)
SELECT material_scope.partNumber AS partNumber, MAX(COALESCE(makt.MAKTX, material_scope.partNumber)) AS name, SUM(lips.LFIMG) AS quantity
FROM material_scope AS material_scope
JOIN QUERY_IT.LIPS AS lips ON lips.MANDT = material_scope.client AND lips.MATNR = material_scope.material AND lips.MANDT = ?
LEFT JOIN QUERY_IT.MAKT AS makt ON makt.MATNR = material_scope.material AND makt.SPRAS = ? AND makt.MANDT = ?
WHERE lips.ERDAT BETWEEN ? AND ? AND EXISTS (SELECT 1
FROM QUERY_IT.VBKD AS vbkd
WHERE vbkd.MANDT = ? AND vbkd.VBELN = lips.VGBEL)
GROUP BY material_scope.partNumber
ORDER BY material_scope.partNumber ASC
