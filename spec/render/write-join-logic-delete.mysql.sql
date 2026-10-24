-- parameters: ["1","0","gone"]
UPDATE `QUERY_IT`.`W_TICKET` `ticket`
JOIN `QUERY_IT`.`W_DOC` `doc` ON `ticket`.`ID` = `doc`.`ID`
SET `ticket`.`DELETED` = ?
WHERE `ticket`.`DELETED` = ? AND `doc`.`TITLE` = ?
