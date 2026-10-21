-- parameters: ["1"]
WITH `ranked_delivery` AS (
SELECT `lips`.`MATNR` AS `material`, `lips`.`VBELN` AS `delivery`, ROW_NUMBER() OVER (PARTITION BY `lips`.`MATNR` ORDER BY `lips`.`ERDAT` DESC, `lips`.`VBELN` DESC) AS `rank`
FROM `QUERY_IT`.`LIPS` `lips`
)
SELECT `ranked_delivery`.`delivery` AS `delivery`
FROM `ranked_delivery` `ranked_delivery`
WHERE `ranked_delivery`.`rank` = ?
ORDER BY `ranked_delivery`.`material` ASC
