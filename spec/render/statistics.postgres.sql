-- parameters: []
SELECT lips."MATNR" AS material, SUM(DISTINCT lips."LFIMG") AS "distinctTotal", AVG(DISTINCT lips."LFIMG") AS "distinctAverage", STDDEV_SAMP(CAST(lips."LFIMG" AS DOUBLE PRECISION)) AS "sampleDeviation", STDDEV_POP(CAST(lips."LFIMG" AS DOUBLE PRECISION)) AS "populationDeviation", VAR_SAMP(CAST(lips."LFIMG" AS DOUBLE PRECISION)) AS "sampleVariance", VAR_POP(CAST(lips."LFIMG" AS DOUBLE PRECISION)) AS "populationVariance"
FROM "QUERY_IT"."LIPS" lips
GROUP BY lips."MATNR"
ORDER BY lips."MATNR" ASC
