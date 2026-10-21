-- parameters: []
SELECT GREATEST(lips."LFIMG", 4) AS "atLeast4", LEAST(lips."LFIMG", 4) AS "atMost4"
FROM "QUERY_IT"."LIPS" lips
