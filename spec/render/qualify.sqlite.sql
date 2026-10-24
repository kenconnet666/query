-- parameters: ["2"]
SELECT q.material AS material, q.delivery AS delivery
FROM (
SELECT lips.MATNR AS material, lips.VBELN AS delivery, ROW_NUMBER() OVER (PARTITION BY lips.MATNR ORDER BY lips.ERDAT DESC) AS _q3, lips.ERDAT AS _o4
FROM QUERY_IT.LIPS AS lips
) q
WHERE q._q3 <= ?
ORDER BY q.material ASC, q._o4 DESC
