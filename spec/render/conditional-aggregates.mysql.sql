-- parameters: []
SELECT `lips`.`MATNR` AS `material`, SUM(CASE WHEN `lips`.`VRKME` = 'KG' THEN `lips`.`LFIMG` END) AS `kg`, COUNT(CASE WHEN `lips`.`VRKME` = 'EA' THEN 1 END) AS `eachLines`, COUNT(DISTINCT CASE WHEN `lips`.`LFIMG` > 0 THEN `lips`.`VRKME` END) AS `units`, GROUP_CONCAT(`lips`.`VBELN` ORDER BY `lips`.`VBELN` ASC SEPARATOR ',') AS `deliveries`
FROM `QUERY_IT`.`LIPS` `lips`
GROUP BY `lips`.`MATNR`
ORDER BY `lips`.`MATNR` ASC
