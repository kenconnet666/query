-- parameters: ["0"]
SELECT ticket.ID AS id
FROM QUERY_IT.W_TICKET AS ticket
WHERE ticket.DELETED = ?
ORDER BY ticket.ID ASC
