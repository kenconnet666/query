-- parameters: []
WITH codes AS (
SELECT '21,32' AS text
UNION ALL
SELECT '05'
)
SELECT codes.text AS text, instr(codes.text, ',') AS comma, CASE WHEN instr(codes.text, ',') > 0 THEN SUBSTRING(codes.text, 1, (instr(codes.text, ',') - 1)) ELSE codes.text END AS head, CASE WHEN instr(codes.text, ',') > 0 THEN SUBSTRING(codes.text, (instr(codes.text, ',') + 1)) END AS tail
FROM codes AS codes
ORDER BY codes.text ASC
