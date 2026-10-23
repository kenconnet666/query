-- parameters: ["1","0","T1"]
UPDATE `QUERY_IT`.`W_TICKET` `ticket`
SET `DELETED` = ?
WHERE `ticket`.`DELETED` = ? AND `ticket`.`ID` = ?
