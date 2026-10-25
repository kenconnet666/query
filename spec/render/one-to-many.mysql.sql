-- parameters: ["100","100","100","100","ZMAT","100","EA"]
SELECT `mara`.`MATNR` AS `material`, (SELECT COUNT(*)
FROM `QUERY_IT`.`LIPS` `lips`
WHERE `lips`.`MANDT` = ? AND `lips`.`MATNR` = `mara`.`MATNR` AND `lips`.`MANDT` = `mara`.`MANDT`) AS `lines`, (SELECT SUM(`lips2`.`LFIMG`)
FROM `QUERY_IT`.`LIPS` `lips2`
WHERE `lips2`.`MANDT` = ? AND `lips2`.`MATNR` = `mara`.`MATNR` AND `lips2`.`MANDT` = `mara`.`MANDT`) AS `total`, `lips_first`.`delivery` AS `latest`
FROM `QUERY_IT`.`MARA` `mara`
LEFT JOIN LATERAL (SELECT `lips3`.`MANDT` AS `client`, `lips3`.`ERDAT` AS `createdOn`, `lips3`.`VBELN` AS `delivery`, `lips3`.`MATNR` AS `material`, `lips3`.`LFIMG` AS `quantity`, `lips3`.`VGBEL` AS `salesOrder`, `lips3`.`VRKME` AS `unit`
FROM `QUERY_IT`.`LIPS` `lips3`
WHERE `lips3`.`MANDT` = ? AND `lips3`.`MATNR` = `mara`.`MATNR` AND `lips3`.`MANDT` = `mara`.`MANDT`
ORDER BY `lips3`.`ERDAT` DESC
LIMIT 1) `lips_first` ON TRUE
WHERE `mara`.`MANDT` = ? AND `mara`.`MTART` = ? AND EXISTS (SELECT 1
FROM `QUERY_IT`.`LIPS` `lips4`
WHERE `lips4`.`MANDT` = ? AND `lips4`.`MATNR` = `mara`.`MATNR` AND `lips4`.`MANDT` = `mara`.`MANDT` AND `lips4`.`VRKME` = ?)
ORDER BY `mara`.`MATNR` ASC
