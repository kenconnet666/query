-- parameters: ["0","2026","0","2026"]
SELECT `mardh`.`MATNR` AS `material`, `mardh`.`LFGJA` AS `year`, `mardh`.`LFMON` AS `period`, COALESCE(`mardh`.`LABST`, ?) AS `quantity`
FROM `QUERY_IT`.`MARDH` `mardh`
WHERE `mardh`.`LFGJA` = ?
UNION ALL
SELECT `mchbh`.`MATNR`, `mchbh`.`LFGJA`, `mchbh`.`LFMON`, COALESCE(`mchbh`.`CLABS`, ?)
FROM `QUERY_IT`.`MCHBH` `mchbh`
WHERE `mchbh`.`LFGJA` = ?
