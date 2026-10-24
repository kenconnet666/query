-- parameters: ["EA","KG","PC"]
SELECT `lips`.`MATNR` AS `material`
FROM `QUERY_IT`.`LIPS` `lips`
WHERE `lips`.`VRKME` = ?
INTERSECT ALL
SELECT `lips2`.`MATNR`
FROM `QUERY_IT`.`LIPS` `lips2`
WHERE `lips2`.`VRKME` = ?
EXCEPT ALL
SELECT `lips3`.`MATNR`
FROM `QUERY_IT`.`LIPS` `lips3`
WHERE `lips3`.`VRKME` = ?
