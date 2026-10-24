-- parameters: ["EA","100","1","100"]
UPDATE `QUERY_IT`.`LIPS` `lips`
SET `VRKME` = ?
WHERE `lips`.`MANDT` = ? AND EXISTS (SELECT 1
FROM (SELECT 1 AS `ONE`) `seed`
LEFT JOIN `QUERY_IT`.`MAKT` `makt` ON `makt`.`MATNR` = `lips`.`MATNR` AND `makt`.`MANDT` = `lips`.`MANDT` AND `makt`.`SPRAS` = ? AND `makt`.`MANDT` = ?
WHERE `makt`.`MAKTX` IS NULL)
