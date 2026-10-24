-- parameters: ["gone"]
DELETE FROM QUERY_IT.W_NOTE AS note
WHERE EXISTS (SELECT 1
FROM QUERY_IT.W_DOC AS doc
WHERE note.ID = doc.ID AND doc.TITLE = ?)
