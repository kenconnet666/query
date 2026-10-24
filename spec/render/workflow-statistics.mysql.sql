-- parameters: []
SELECT `procdef`.`ID` AS `id`, `procdef`.`DEF_KEY` AS `defKey`, `procdef`.`VERSION` AS `version`, COALESCE(`ProcDef_g`.`c1`, 0) AS `running`, COALESCE(`ProcDef_g`.`c2`, 0) AS `finished`, `ProcDef_g`.`c3` AS `avgDuration`
FROM `QUERY_IT`.`WF_PROCDEF` `procdef`
LEFT JOIN (
SELECT `procinst`.`DEF_ID` AS `k1`, COUNT(CASE WHEN `procinst`.`END_TIME` IS NULL THEN 1 END) AS `c1`, COUNT(CASE WHEN `procinst`.`END_TIME` IS NOT NULL THEN 1 END) AS `c2`, AVG(`procinst`.`DURATION`) AS `c3`
FROM `QUERY_IT`.`WF_PROCINST` `procinst`
GROUP BY `procinst`.`DEF_ID`
) `ProcDef_g` ON `ProcDef_g`.`k1` = `procdef`.`ID`
ORDER BY `procdef`.`ID` ASC
