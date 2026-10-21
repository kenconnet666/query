-- parameters: []
WITH "codes" AS (
SELECT '21,32' AS "text"
FROM DUMMY
UNION ALL
SELECT '05'
FROM DUMMY
)
SELECT CODES."text" AS "text", LOCATE(CODES."text", ',') AS "comma", CASE WHEN LOCATE(CODES."text", ',') > 0 THEN SUBSTRING(CODES."text", 1, (LOCATE(CODES."text", ',') - 1)) ELSE CODES."text" END AS "head", CASE WHEN LOCATE(CODES."text", ',') > 0 THEN SUBSTRING(CODES."text", (LOCATE(CODES."text", ',') + 1)) END AS "tail"
FROM "codes" CODES
ORDER BY CODES."text" ASC
