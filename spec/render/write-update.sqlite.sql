-- parameters: ["10","closed","N1","open"]
UPDATE QUERY_IT.W_NOTE AS note
SET QTY = (note.QTY + ?), STATUS = ?
WHERE note.ID = ? AND note.STATUS = ?
