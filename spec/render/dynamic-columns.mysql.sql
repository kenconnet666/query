-- parameters: []
SELECT `lips`.`MATNR` AS `material`, NULL AS `unit`, NULL AS `partNumber`, SUM(`lips`.`LFIMG`) AS `total`
FROM `QUERY_IT`.`LIPS` `lips`
GROUP BY `lips`.`MATNR`
ORDER BY SUM(`lips`.`LFIMG`) DESC, `lips`.`MATNR` ASC
