-- parameters: ["2011-01-01","2025-01-01"]
SELECT event.ID AS id, CAST(strftime('%Y', event.ON_DAY) AS INTEGER) AS y, CAST(strftime('%m', event.AT_TIME) AS INTEGER) AS m, CAST(strftime('%d', event.ON_DAY) AS INTEGER) AS d, date(event.ON_DAY, 3 || ' days') AS plusDays, date(event.ON_DAY, -1 || ' months', 'floor') AS minusMonth, date(event.ON_DAY, 1 || ' years', 'floor') AS plusYear, CAST(julianday('2024-04-30') - julianday(event.ON_DAY) AS INTEGER) AS days
FROM QUERY_IT.EVENTS AS event
WHERE event.ON_DAY >= ? AND event.ON_DAY < ?
ORDER BY event.ID ASC
