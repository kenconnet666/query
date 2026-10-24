-- parameters: ["1"]
UPDATE QUERY_IT.W_NOTE AS note
SET TITLE = doc.TITLE
FROM QUERY_IT.W_DOC AS doc
WHERE note.ID = doc.ID AND doc.VERSION > ?
