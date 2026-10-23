-- parameters: ["alice","candidate","alice","managers","leads","leave","%app%"]
SELECT `task`.`ID` AS `id`, `task`.`NAME` AS `name`, `task`.`ASSIGNEE` AS `assignee`, `procdef`.`DEF_KEY` AS `process`, `procinst`.`BUSINESS_KEY` AS `businessKey`
FROM `QUERY_IT`.`WF_TASK` `task`
LEFT JOIN `QUERY_IT`.`WF_PROCDEF` `procdef` ON `procdef`.`ID` = `task`.`DEF_ID`
LEFT JOIN `QUERY_IT`.`WF_PROCINST` `procinst` ON `procinst`.`ID` = `task`.`PROC_INST_ID`
WHERE ((`task`.`ASSIGNEE` = ?) OR (`task`.`ASSIGNEE` IS NULL AND EXISTS (SELECT 1
FROM `QUERY_IT`.`WF_LINK` `link`
WHERE `link`.`TASK_ID` = `task`.`ID` AND `link`.`KIND` = ? AND ((`link`.`USER_ID` = ?) OR (`link`.`GROUP_ID` IN (?, ?)))))) AND `procdef`.`DEF_KEY` = ? AND `task`.`NAME` LIKE ? ESCAPE '!'
ORDER BY `task`.`CREATE_TIME` DESC, `task`.`ID` ASC
