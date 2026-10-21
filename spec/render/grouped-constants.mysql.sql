-- parameters: ["EA"]
SELECT CASE WHEN `lips`.`LFIMG` <= 2 THEN 'small' WHEN `lips`.`LFIMG` <= 5 THEN 'medium' ELSE 'large' END AS `bucket`, SUBSTRING(`lips`.`VBELN`, 1, 1) AS `prefix`, COUNT(*) AS `lines`, SUM(CASE WHEN `lips`.`VRKME` = 'EA' THEN `lips`.`LFIMG` ELSE 0 END) AS `total`
FROM `QUERY_IT`.`LIPS` `lips`
WHERE `lips`.`VRKME` = ?
GROUP BY CASE WHEN `lips`.`LFIMG` <= 2 THEN 'small' WHEN `lips`.`LFIMG` <= 5 THEN 'medium' ELSE 'large' END, SUBSTRING(`lips`.`VBELN`, 1, 1)
HAVING COUNT(*) > 1
ORDER BY CASE WHEN `lips`.`LFIMG` <= 2 THEN 'small' WHEN `lips`.`LFIMG` <= 5 THEN 'medium' ELSE 'large' END ASC
