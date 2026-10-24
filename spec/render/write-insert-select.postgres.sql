-- parameters: ["N1"]
INSERT INTO "QUERY_IT"."W_NOTE" ("ID", "TENANT", "TITLE", "QTY", "STATUS")
SELECT 'N9' AS id, note."TENANT" AS tenant, note."TITLE" AS title, note."QTY" AS qty, note."STATUS" AS status
FROM "QUERY_IT"."W_NOTE" note
WHERE note."ID" = ?
