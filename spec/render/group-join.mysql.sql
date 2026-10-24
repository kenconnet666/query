-- parameters: ["100","100"]
SELECT `mara`.`MATNR` AS `material`, COALESCE(`Mara_g`.`c1`, 0) AS `lines`, `Mara_g`.`c2` AS `total`, COALESCE(`Mara_g`.`c3`, 0) AS `recent`
FROM `QUERY_IT`.`MARA` `mara`
LEFT JOIN (
SELECT `lips`.`MATNR` AS `k1`, `lips`.`MANDT` AS `k2`, COUNT(*) AS `c1`, SUM(`lips`.`LFIMG`) AS `c2`, COUNT(CASE WHEN `lips`.`ERDAT` >= '20260101' THEN 1 END) AS `c3`, COUNT(CASE WHEN `lips`.`VRKME` = 'EA' THEN 1 END) AS `c4`
FROM `QUERY_IT`.`LIPS` `lips`
WHERE `lips`.`MANDT` = ?
GROUP BY `lips`.`MATNR`, `lips`.`MANDT`
) `Mara_g` ON `Mara_g`.`k1` = `mara`.`MATNR` AND `Mara_g`.`k2` = `mara`.`MANDT`
WHERE `mara`.`MANDT` = ? AND COALESCE(`Mara_g`.`c4`, 0) > 0
ORDER BY `mara`.`MATNR` ASC
