-- parameters: ["gone"]
DELETE `note` FROM `QUERY_IT`.`W_NOTE` `note`
JOIN `QUERY_IT`.`W_DOC` `doc` ON `note`.`ID` = `doc`.`ID`
WHERE `doc`.`TITLE` = ?
