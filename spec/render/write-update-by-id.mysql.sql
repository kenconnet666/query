-- parameters: ["new","D1","3"]
UPDATE `QUERY_IT`.`W_DOC` `doc`
SET `TITLE` = ?, `VERSION` = (`doc`.`VERSION` + 1)
WHERE `doc`.`ID` = ? AND `doc`.`VERSION` = ?
