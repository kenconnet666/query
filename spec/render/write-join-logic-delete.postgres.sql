-- parameters: ["1","0","gone"]
UPDATE "QUERY_IT"."W_TICKET" ticket
SET "DELETED" = ?
FROM "QUERY_IT"."W_DOC" doc
WHERE ticket."ID" = doc."ID" AND ticket."DELETED" = ? AND doc."TITLE" = ?
