-- parameters: []
WITH `category_def` AS (
SELECT '1' AS `zfl`, '自制件' AS `name`, 1 AS `sortNo`
UNION ALL
SELECT '2', '供应商件', 2
)
SELECT `category_def`.`zfl` AS `zfl`, `category_def`.`name` AS `name`
FROM `category_def` `category_def`
ORDER BY `category_def`.`sortNo` ASC
