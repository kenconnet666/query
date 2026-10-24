-- parameters: ["1","3","5"]
SELECT shorder.CUSTOMER AS customer, shorder.TOTAL AS total
FROM QUERY_IT.SH_ORDER_1 AS shorder
WHERE ((shorder.CUSTOMER IN (?, ?)) OR (shorder.CUSTOMER = ?))
