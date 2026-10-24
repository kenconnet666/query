-- parameters: ["1"]
UPDATE `QUERY_IT`.`W_NOTE` `note`
JOIN `QUERY_IT`.`W_DOC` `doc` ON `note`.`ID` = `doc`.`ID`
SET `note`.`TITLE` = `doc`.`TITLE`
WHERE `doc`.`VERSION` > ?
