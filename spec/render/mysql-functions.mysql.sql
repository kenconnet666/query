-- parameters: []
SELECT /*+ MAX_EXECUTION_TIME(2000) */ `lips`.`MATNR` AS `material`, DATEDIFF(DATE '2026-02-01', DATE '2026-01-01') AS `days`, DATE_FORMAT(DATE '2026-01-31', '%Y%m%d') AS `formatted`, GROUP_CONCAT(`lips`.`VBELN` ORDER BY `lips`.`VBELN` ASC SEPARATOR '|') AS `deliveries`
FROM `QUERY_IT`.`LIPS` `lips`
GROUP BY `lips`.`MATNR`
