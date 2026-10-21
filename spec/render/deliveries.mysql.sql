-- parameters: ["ZMAT","1","20260101","20260630"]
WITH `material_scope` AS (
SELECT DISTINCT `mara`.`MANDT` AS `client`, `mara`.`MATNR` AS `material`, CASE WHEN `mara`.`MFRPN` <> TRIM(`mara`.`MFRPN`) THEN TRIM(`mara`.`MFRPN`) ELSE `mara`.`MFRPN` END AS `partNumber`
FROM `QUERY_IT`.`MARA` `mara`
WHERE `mara`.`MTART` = ?
)
SELECT `material_scope`.`partNumber` AS `partNumber`, MAX(COALESCE(`makt`.`MAKTX`, `material_scope`.`partNumber`)) AS `name`, SUM(`lips`.`LFIMG`) AS `quantity`
FROM `material_scope` `material_scope`
JOIN `QUERY_IT`.`LIPS` `lips` ON `lips`.`MANDT` = `material_scope`.`client` AND `lips`.`MATNR` = `material_scope`.`material`
LEFT JOIN `QUERY_IT`.`MAKT` `makt` ON `makt`.`MATNR` = `material_scope`.`material` AND `makt`.`SPRAS` = ?
WHERE `lips`.`ERDAT` BETWEEN ? AND ? AND EXISTS (SELECT 1
FROM `QUERY_IT`.`VBKD` `vbkd`
WHERE `vbkd`.`VBELN` = `lips`.`VGBEL`)
GROUP BY `material_scope`.`partNumber`
ORDER BY `material_scope`.`partNumber` ASC
