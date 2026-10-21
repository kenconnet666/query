-- parameters: ["1"]
SELECT `lips`.`MATNR` AS `material`, COUNT(*) AS `lines`, COUNT(DISTINCT `lips`.`VGBEL`) AS `orders`, AVG(`lips`.`LFIMG`) AS `average`, MIN(`lips`.`LFIMG`) AS `smallest`, MAX(`lips`.`LFIMG`) AS `largest`, SUM(`lips`.`LFIMG`) AS `total`
FROM `QUERY_IT`.`LIPS` `lips`
GROUP BY `lips`.`MATNR`
HAVING COUNT(*) > ?
