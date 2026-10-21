-- parameters: []
SELECT mara."MATNR" AS material, CASE mara."MTART" WHEN 'ZMAT' THEN 'material' ELSE 'other' END AS kind, COALESCE(NULLIF(mara."MFRNR", ''), NULLIF(mara."MFRPN", ''), 'none') AS maker
FROM "QUERY_IT"."MARA" mara
ORDER BY mara."MATNR" ASC
