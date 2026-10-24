-- parameters: ["simple","simple","pump","english","english","valve -leak","simple","simple","pump"]
SELECT tagged."ID" AS id
FROM "QUERY_IT"."PG_TAGGED" tagged
WHERE ((to_tsvector(CAST(? AS regconfig), tagged."BODY") @@ plainto_tsquery(CAST(? AS regconfig), ?)) OR (to_tsvector(CAST(? AS regconfig), tagged."BODY") @@ websearch_to_tsquery(CAST(? AS regconfig), ?)))
ORDER BY CAST(ts_rank(to_tsvector(CAST(? AS regconfig), tagged."BODY"), plainto_tsquery(CAST(? AS regconfig), ?)) AS DOUBLE PRECISION) DESC
