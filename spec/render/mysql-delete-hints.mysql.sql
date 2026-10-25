-- parameters: ["100","D9"]
DELETE /*+ INDEX(`lips` `PRIMARY`) */ FROM `QUERY_IT`.`LIPS` `lips`
WHERE `lips`.`MANDT` = ? AND `lips`.`VBELN` = ?
