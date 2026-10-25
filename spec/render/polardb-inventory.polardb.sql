-- parameters: ["1","D1","0"]
UPDATE /*+ COMMIT_ON_SUCCESS ROLLBACK_ON_FAIL TARGET_AFFECT_ROW(1) NO_INDEX(`lips`) */ `QUERY_IT`.`LIPS` `lips`
SET `LFIMG` = (`lips`.`LFIMG` - ?)
WHERE `lips`.`VBELN` = ? AND `lips`.`LFIMG` > ?
