-- parameters: ["1"]
UPDATE "QUERY_IT"."W_NOTE" note
SET "TITLE" = doc."TITLE"
FROM "QUERY_IT"."W_DOC" doc
WHERE note."ID" = doc."ID" AND doc."VERSION" > ?
