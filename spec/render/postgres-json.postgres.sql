-- parameters: ["{\"status\":\"open\"}","owner"]
SELECT tagged."ID" AS id, (tagged."DOC" #>> CAST(ARRAY['owner', 'name'] AS TEXT[])) AS owner
FROM "QUERY_IT"."PG_TAGGED" tagged
WHERE tagged."DOC" @> CAST(? AS JSONB) AND jsonb_exists(tagged."DOC", ?)
