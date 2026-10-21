-- parameters: []
SELECT `lips`.`MATNR` AS `material`, `lips`.`VRKME` AS `unit`, SUM(`lips`.`LFIMG`) AS `total`, GROUPING(`lips`.`MATNR`) AS `level`
FROM `QUERY_IT`.`LIPS` `lips`
GROUP BY `lips`.`MATNR`, `lips`.`VRKME` WITH ROLLUP
ORDER BY GROUPING(`lips`.`MATNR`) ASC, `lips`.`MATNR` ASC, GROUPING(`lips`.`VRKME`) ASC
