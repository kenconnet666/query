-- parameters: []
WITH "category_def" AS (
SELECT '1' AS "zfl", N'自制件' AS "name", 1 AS "sortNo"
FROM DUMMY
UNION ALL
SELECT '2', N'供应商件', 2
FROM DUMMY
)
SELECT CATEGORY_DEF."zfl" AS "zfl", CATEGORY_DEF."name" AS "name"
FROM "category_def" CATEGORY_DEF
ORDER BY CATEGORY_DEF."sortNo" ASC
