-- parameters: []
SELECT LEFT(`lips`.`VBELN`, 2) AS `prefix`, RIGHT(`lips`.`VBELN`, 3) AS `suffix`, SUBSTRING(`lips`.`MATNR`, 2, 3) AS `middle`, SUBSTRING(`lips`.`MATNR`, 2) AS `tail`, REPLACE(`lips`.`VRKME`, '-', '') AS `cleaned`, -(`lips`.`LFIMG`) AS `negative`, (`lips`.`LFIMG` + COALESCE(`lips`.`LFIMG`, 0)) AS `periods`
FROM `QUERY_IT`.`LIPS` `lips`
