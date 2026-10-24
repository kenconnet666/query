-- parameters: ["D1","3"]
WITH RECURSIVE steps AS (
SELECT lips."VBELN" AS delivery, 1 AS depth
FROM "QUERY_IT"."LIPS" lips
WHERE lips."VBELN" = ?
UNION ALL
SELECT steps2.delivery, (steps2.depth + 1)
FROM steps steps2
WHERE steps2.depth < ?
)
SELECT steps.depth AS depth
FROM steps steps
ORDER BY steps.depth ASC
