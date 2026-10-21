-- parameters: ["ZMAT"]
SELECT `t`.`delivery` AS `delivery`, (SELECT MAX(`makt`.`MAKTX`)
FROM `QUERY_IT`.`MAKT` `makt`
WHERE `makt`.`MATNR` = `t`.`material`) AS `text`
FROM (SELECT `lips`.`VBELN` AS `delivery`, `lips`.`MATNR` AS `material`
FROM `QUERY_IT`.`LIPS` `lips`) `t`
WHERE `t`.`material` IN (SELECT `mara`.`MATNR`
FROM `QUERY_IT`.`MARA` `mara`
WHERE `mara`.`MTART` = ?) AND NOT EXISTS (SELECT 1
FROM `QUERY_IT`.`VBKD` `vbkd`
WHERE `vbkd`.`VBELN` = `t`.`delivery`)
