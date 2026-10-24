-- parameters: []
SELECT procdef.ID AS id
FROM QUERY_IT.WF_PROCDEF AS procdef
WHERE procdef.VERSION = (SELECT MAX(procdef2.VERSION)
FROM QUERY_IT.WF_PROCDEF AS procdef2
WHERE procdef2.DEF_KEY = procdef.DEF_KEY)
ORDER BY procdef.DEF_KEY ASC
