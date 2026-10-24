-- parameters: []
SELECT `hitask`.`ID` AS `id`, TIMESTAMPDIFF(SECOND, `hitask`.`START_TIME`, `hitask`.`END_TIME`) AS `seconds`
FROM `QUERY_IT`.`WF_HI_TASK` `hitask`
ORDER BY `hitask`.`ID` ASC
