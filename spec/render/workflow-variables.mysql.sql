-- parameters: ["amount","1000","dept","X"]
SELECT `procinst`.`ID` AS `id`
FROM `QUERY_IT`.`WF_PROCINST` `procinst`
WHERE `procinst`.`END_TIME` IS NULL AND EXISTS (SELECT 1
FROM `QUERY_IT`.`WF_VARIABLE` `variable`
WHERE `variable`.`PROC_INST_ID` = `procinst`.`ID` AND `variable`.`NAME` = ? AND `variable`.`LONG_VALUE` > ?) AND EXISTS (SELECT 1
FROM `QUERY_IT`.`WF_VARIABLE` `variable2`
WHERE `variable2`.`PROC_INST_ID` = `procinst`.`ID` AND `variable2`.`NAME` = ? AND `variable2`.`TEXT_VALUE` = ?)
ORDER BY `procinst`.`ID` ASC
