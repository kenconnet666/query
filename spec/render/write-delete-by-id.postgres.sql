-- parameters: ["D1","3"]
DELETE FROM "QUERY_IT"."W_DOC" doc
WHERE doc."ID" = ? AND doc."VERSION" = ?
