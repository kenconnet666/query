-- parameters: []
SELECT /*+ MAX_EXECUTION_TIME(2000) INDEX(`lips` `PRIMARY`) NO_INDEX(`mara`) */ `lips`.`VBELN` AS `delivery`
FROM `QUERY_IT`.`LIPS` `lips`
JOIN `QUERY_IT`.`MARA` `mara` ON `mara`.`MATNR` = `lips`.`MATNR`
