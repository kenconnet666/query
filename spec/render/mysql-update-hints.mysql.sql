-- parameters: ["KG","100","D1"]
UPDATE /*+ NO_INDEX(`lips` `IX_LIPS`) MAX_EXECUTION_TIME(2000) */ `QUERY_IT`.`LIPS` `lips`
SET `VRKME` = ?
WHERE `lips`.`MANDT` = ? AND `lips`.`VBELN` = ?
