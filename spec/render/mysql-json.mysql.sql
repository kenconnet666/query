-- parameters: ["{\"status\":\"open\"}"]
SELECT `article`.`ID` AS `id`, CASE WHEN JSON_TYPE(JSON_EXTRACT(`article`.`DOC`, CONVERT('$.owner.name' USING utf8mb4))) IN ('OBJECT', 'ARRAY', 'NULL') THEN NULL ELSE JSON_UNQUOTE(JSON_EXTRACT(`article`.`DOC`, CONVERT('$.owner.name' USING utf8mb4))) END AS `owner`
FROM `QUERY_IT`.`MY_ARTICLE` `article`
WHERE JSON_CONTAINS(`article`.`DOC`, CAST(CONVERT(? USING utf8mb4) AS JSON))
