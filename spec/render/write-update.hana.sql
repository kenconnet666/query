-- parameters: ["10","closed","N1","open"]
UPDATE QUERY_IT.W_NOTE NOTE
SET QTY = (NOTE.QTY + ?), STATUS = ?
WHERE NOTE.ID = ? AND NOTE.STATUS = ?
