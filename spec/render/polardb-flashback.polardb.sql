-- parameters: ["2026-01-15 08:30:00","100","M1"]
SELECT `lips`.`VBELN` AS `delivery`, `lips`.`LFIMG` AS `quantity`
FROM `QUERY_IT`.`LIPS` AS OF TIMESTAMP CAST(? AS DATETIME(6)) `lips`
WHERE `lips`.`MANDT` = ? AND `lips`.`MATNR` = ?
ORDER BY `lips`.`VBELN` ASC
