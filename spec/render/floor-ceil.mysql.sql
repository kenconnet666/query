-- parameters: []
SELECT `lips`.`VBELN` AS `delivery`, FLOOR((`lips`.`LFIMG` / 2)) AS `down`, CEIL((`lips`.`LFIMG` / 2)) AS `up`
FROM `QUERY_IT`.`LIPS` `lips`
ORDER BY `lips`.`VBELN` ASC
