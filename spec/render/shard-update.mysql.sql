-- parameters: ["x","2026-03-01","2026-03-31"]
UPDATE `QUERY_IT`.`SH_EVENT_202603` `shevent`
SET `NOTE` = ?
WHERE `shevent`.`DAY` BETWEEN ? AND ?
