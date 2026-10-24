-- parameters: ["E*"]
SELECT lips.MATNR AS material, median(CAST(lips.LFIMG AS REAL)) AS middle, percentile_cont(CAST(lips.LFIMG AS REAL), 0.9) AS p90, percentile_disc(lips.LFIMG, 0.5) AS p50, CASE WHEN length(lips.MATNR) >= 5 THEN substr(lips.MATNR, 1, 5) ELSE substr(replace(hex(zeroblob(5)), '00', '0'), 1, 5 - length(lips.MATNR)) || lips.MATNR END AS padded, substr(lips.MATNR, 1, 1) AS head
FROM QUERY_IT.LIPS AS lips
WHERE lips.VRKME GLOB ?
GROUP BY lips.MATNR
