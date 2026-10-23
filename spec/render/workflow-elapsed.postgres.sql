-- parameters: []
SELECT hitask."ID" AS id, CAST(TRUNC(EXTRACT(EPOCH FROM (hitask."END_TIME" - hitask."START_TIME"))) AS BIGINT) AS seconds
FROM "QUERY_IT"."WF_HI_TASK" hitask
ORDER BY hitask."ID" ASC
