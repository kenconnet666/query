-- parameters: []
SELECT lips.VBELN AS delivery, floor((CAST(lips.LFIMG AS REAL) / 2)) AS down, ceil((CAST(lips.LFIMG AS REAL) / 2)) AS up
FROM QUERY_IT.LIPS AS lips
ORDER BY lips.VBELN ASC
