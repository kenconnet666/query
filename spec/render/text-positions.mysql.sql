-- parameters: []
WITH `codes` AS (
SELECT '21,32' AS `text`
UNION ALL
SELECT '05'
)
SELECT `codes`.`text` AS `text`, LOCATE(',', `codes`.`text`) AS `comma`, CASE WHEN LOCATE(',', `codes`.`text`) > 0 THEN SUBSTRING(`codes`.`text`, 1, (LOCATE(',', `codes`.`text`) - 1)) ELSE `codes`.`text` END AS `head`, CASE WHEN LOCATE(',', `codes`.`text`) > 0 THEN SUBSTRING(`codes`.`text`, (LOCATE(',', `codes`.`text`) + 1)) END AS `tail`
FROM `codes` `codes`
ORDER BY `codes`.`text` ASC
