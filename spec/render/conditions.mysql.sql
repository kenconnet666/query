-- parameters: ["A","B","ZMAT","ZOTH","X","P%","%X","A","Z","0","1","9","8","A"]
SELECT `mara`.`MATNR` AS `material`
FROM `QUERY_IT`.`MARA` `mara`
WHERE ((`mara`.`MATNR` = ?) OR (`mara`.`MFRPN` <> ? AND `mara`.`MFRNR` IS NULL)) AND NOT (`mara`.`MTART` IN (?, ?)) AND `mara`.`MATNR` NOT IN (?) AND `mara`.`MFRPN` LIKE ? AND `mara`.`MFRNR` NOT LIKE ? AND `mara`.`MATNR` BETWEEN ? AND ? AND `mara`.`MFRNR` IS NOT NULL AND `mara`.`MATNR` > ? AND `mara`.`MATNR` >= ? AND `mara`.`MATNR` < ? AND `mara`.`MATNR` <= ? AND `mara`.`MATNR` >= ?
