-- parameters: []
SELECT CASE WHEN `mara`.`MTART` = 'ZMAT' THEN 'material' WHEN `mara`.`MTART` = 'ZOTH' THEN 'other' ELSE LOWER(`mara`.`MTART`) END AS `value`
FROM `QUERY_IT`.`MARA` `mara`
