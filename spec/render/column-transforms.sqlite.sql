-- parameters: [""]
SELECT TRIM(maraview.MFRPN) AS partNumber, COUNT(DISTINCT NULLIF(maraview.MFRNR, '')) AS makers
FROM QUERY_IT.MARA AS maraview
WHERE NULLIF(maraview.MFRNR, '') IS NOT NULL AND maraview.MFRNR <> ?
GROUP BY TRIM(maraview.MFRPN)
