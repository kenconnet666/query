-- parameters: ["gone"]
DELETE FROM "QUERY_IT"."W_NOTE" note
USING "QUERY_IT"."W_DOC" doc
WHERE note."ID" = doc."ID" AND doc."TITLE" = ?
