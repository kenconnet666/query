-- parameters: ["pump","+valve -leak","pump"]
SELECT `article`.`ID` AS `id`
FROM `QUERY_IT`.`MY_ARTICLE` `article`
WHERE ((MATCH (`article`.`TITLE`, `article`.`BODY`) AGAINST (? IN NATURAL LANGUAGE MODE)) OR (MATCH (`article`.`TITLE`, `article`.`BODY`) AGAINST (? IN BOOLEAN MODE)))
ORDER BY MATCH (`article`.`TITLE`, `article`.`BODY`) AGAINST (? IN NATURAL LANGUAGE MODE) DESC
