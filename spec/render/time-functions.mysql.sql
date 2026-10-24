-- parameters: []
SELECT `event`.`ID` AS `id`, HOUR(`event`.`AT_TIME`) AS `h`, MINUTE(`event`.`AT_TIME`) AS `mi`, SECOND(`event`.`AT_TIME`) AS `s`, TIMESTAMPADD(SECOND, 90000, `event`.`AT_TIME`) AS `plusHours`, TIMESTAMPADD(SECOND, -1860, `event`.`AT_TIME`) AS `minusMinutes`, TIMESTAMPADD(SECOND, 59, `event`.`AT_TIME`) AS `plusSeconds`, DATE_SUB(`event`.`ON_DAY`, INTERVAL DAYOFMONTH(`event`.`ON_DAY`) - 1 DAY) AS `monthStart`
FROM `QUERY_IT`.`EVENTS` `event`
ORDER BY `event`.`ID` ASC
