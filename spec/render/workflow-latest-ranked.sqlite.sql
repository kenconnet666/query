-- parameters: ["1"]
SELECT q.id AS id
FROM (
SELECT procdef.ID AS id, ROW_NUMBER() OVER (PARTITION BY procdef.DEF_KEY ORDER BY procdef.VERSION DESC) AS _q2, procdef.DEF_KEY AS _o3
FROM QUERY_IT.WF_PROCDEF AS procdef
) q
WHERE q._q2 = ?
ORDER BY q._o3 ASC
