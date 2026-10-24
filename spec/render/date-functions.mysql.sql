-- parameters: ["2011-01-01","2025-01-01"]
SELECT `event`.`ID` AS `id`, YEAR(`event`.`ON_DAY`) AS `y`, MONTH(`event`.`AT_TIME`) AS `m`, DAYOFMONTH(`event`.`ON_DAY`) AS `d`, DATE_ADD(`event`.`ON_DAY`, INTERVAL 3 DAY) AS `plusDays`, DATE_ADD(`event`.`ON_DAY`, INTERVAL -1 MONTH) AS `minusMonth`, DATE_ADD(`event`.`ON_DAY`, INTERVAL 1 YEAR) AS `plusYear`, DATEDIFF(DATE '2024-04-30', `event`.`ON_DAY`) AS `days`
FROM `QUERY_IT`.`EVENTS` `event`
WHERE `event`.`ON_DAY` >= ? AND `event`.`ON_DAY` < ?
ORDER BY `event`.`ID` ASC
