-- parameters: ["100","100","100","EA","100","KG","100","PC"]
SELECT `lips`.`VBELN` AS `delivery`, `mara`.`MFRPN` AS `partNumber`, CASE WHEN `lips`.`LFIMG` > 1 THEN TRUE WHEN NOT (`lips`.`LFIMG` > 1) THEN FALSE END AS `big`
FROM `QUERY_IT`.`MARA` `mara`
RIGHT JOIN `QUERY_IT`.`LIPS` `lips` ON `mara`.`MATNR` = `lips`.`MATNR` AND `mara`.`MANDT` = ?
WHERE `lips`.`MANDT` = ? AND (`lips`.`VBELN`, `lips`.`MATNR`) IN (SELECT `lips2`.`VBELN`, `lips2`.`MATNR`
FROM `QUERY_IT`.`LIPS` `lips2`
WHERE `lips2`.`MANDT` = ? AND `lips2`.`VRKME` = ?) AND `lips`.`LFIMG` >= ANY (SELECT `lips3`.`LFIMG`
FROM `QUERY_IT`.`LIPS` `lips3`
WHERE `lips3`.`MANDT` = ? AND `lips3`.`VRKME` = ?) AND `lips`.`LFIMG` < ALL (SELECT `lips4`.`LFIMG`
FROM `QUERY_IT`.`LIPS` `lips4`
WHERE `lips4`.`MANDT` = ? AND `lips4`.`VRKME` = ?) AND NOT (`mara`.`MFRNR` <=> `mara`.`MFRPN`) AND NOT (`lips`.`VRKME` <=> `mara`.`MTART`)
ORDER BY `lips`.`VBELN` ASC
