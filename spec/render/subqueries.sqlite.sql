-- parameters: ["ZMAT"]
SELECT t.delivery AS delivery, (SELECT MAX(makt.MAKTX)
FROM QUERY_IT.MAKT AS makt
WHERE makt.MATNR = t.material) AS text
FROM (SELECT lips.VBELN AS delivery, lips.MATNR AS material
FROM QUERY_IT.LIPS AS lips) AS t
WHERE t.material IN (SELECT mara.MATNR
FROM QUERY_IT.MARA AS mara
WHERE mara.MTART = ?) AND NOT EXISTS (SELECT 1
FROM QUERY_IT.VBKD AS vbkd
WHERE vbkd.VBELN = t.delivery)
