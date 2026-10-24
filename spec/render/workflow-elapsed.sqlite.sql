-- parameters: []
SELECT hitask.ID AS id, CAST(unixepoch(hitask.END_TIME, 'subsec') - unixepoch(hitask.START_TIME, 'subsec') AS INTEGER) AS seconds
FROM QUERY_IT.WF_HI_TASK AS hitask
ORDER BY hitask.ID ASC
