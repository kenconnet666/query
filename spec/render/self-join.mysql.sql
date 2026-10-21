-- parameters: []
SELECT `mara`.`MATNR` AS `material`
FROM `QUERY_IT`.`MARA` `mara`
JOIN `QUERY_IT`.`MARA` `mara2` ON `mara2`.`MFRPN` = `mara`.`MFRPN`
WHERE EXISTS (SELECT 1
FROM `QUERY_IT`.`MARA` `mara3`
WHERE `mara3`.`MATNR` = `mara2`.`MATNR`)
