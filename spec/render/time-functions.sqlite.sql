-- parameters: []
SELECT event.ID AS id, CAST(strftime('%H', event.AT_TIME) AS INTEGER) AS h, CAST(strftime('%M', event.AT_TIME) AS INTEGER) AS mi, CAST(strftime('%S', event.AT_TIME) AS INTEGER) AS s, rtrim(rtrim(strftime('%Y-%m-%d %H:%M:%f', event.AT_TIME, 90000 || ' seconds'), '0'), '.') AS plusHours, rtrim(rtrim(strftime('%Y-%m-%d %H:%M:%f', event.AT_TIME, -1860 || ' seconds'), '0'), '.') AS minusMinutes, rtrim(rtrim(strftime('%Y-%m-%d %H:%M:%f', event.AT_TIME, 59 || ' seconds'), '0'), '.') AS plusSeconds, date(event.ON_DAY, 'start of month') AS monthStart
FROM QUERY_IT.EVENTS AS event
ORDER BY event.ID ASC
