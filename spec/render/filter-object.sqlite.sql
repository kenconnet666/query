-- parameters: ["1","M1","M2","20260101","%50!%%","%50!%%","KG"]
SELECT lips.VBELN AS delivery
FROM QUERY_IT.LIPS AS lips
LEFT JOIN QUERY_IT.MARA AS mara ON mara.MATNR = lips.MATNR AND mara.MANDT = lips.MANDT
LEFT JOIN QUERY_IT.MAKT AS makt ON makt.MATNR = lips.MATNR AND makt.MANDT = lips.MANDT AND makt.SPRAS = ?
WHERE lips.MATNR IN (?, ?) AND lips.ERDAT >= ? AND ((mara.MFRPN LIKE ? ESCAPE '!') OR (makt.MAKTX LIKE ? ESCAPE '!')) AND lips.VRKME <> ?
ORDER BY lips.VBELN ASC
