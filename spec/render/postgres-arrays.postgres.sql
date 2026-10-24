-- parameters: ["1,2","red","blue,green"]
SELECT tagged."ID" AS id, tagged."TAGS" AS tags, cardinality(tagged."TAGS") AS count
FROM "QUERY_IT"."PG_TAGGED" tagged
WHERE tagged."ID" = ANY(?) AND tagged."TAGS" @> ? AND tagged."TAGS" && ?
ORDER BY tagged."ID" ASC
