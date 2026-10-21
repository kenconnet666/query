-- parameters: []
SELECT `mara`.`MATNR` AS `material`
FROM `QUERY_IT`.`MARA` `mara`
ORDER BY `mara`.`MATNR` IS NULL ASC, `mara`.`MATNR` ASC, `mara`.`MFRPN` IS NULL DESC, `mara`.`MFRPN` DESC
LIMIT 10 OFFSET 20
