-- parameters: ["closed"]
DELETE FROM QUERY_IT.W_NOTE AS note
WHERE note.STATUS = ?
