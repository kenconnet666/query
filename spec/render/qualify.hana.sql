-- parameters: ["2"]
SELECT Q."material" AS "material", Q."delivery" AS "delivery"
FROM (
SELECT LIPS.MATNR AS "material", LIPS.VBELN AS "delivery", ROW_NUMBER() OVER (PARTITION BY LIPS.MATNR ORDER BY LIPS.ERDAT DESC) AS "_q3", LIPS.ERDAT AS "_o4"
FROM QUERY_IT.LIPS LIPS
) Q
WHERE Q."_q3" <= ?
ORDER BY Q."material" ASC, Q."_o4" DESC
