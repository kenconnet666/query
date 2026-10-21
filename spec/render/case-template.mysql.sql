-- parameters: ["ZMAT","material","ZOTH","other"]
SELECT CASE WHEN `mara`.`MTART` = ? THEN ? WHEN `mara`.`MTART` = ? THEN ? ELSE LOWER(`mara`.`MTART`) END AS `value`
FROM `QUERY_IT`.`MARA` `mara`
