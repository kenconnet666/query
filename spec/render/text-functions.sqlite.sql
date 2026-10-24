-- parameters: []
SELECT substr(lips.VBELN, 1, 2) AS prefix, CASE WHEN 3 > 0 THEN substr(lips.VBELN, -3) ELSE '' END AS suffix, SUBSTRING(lips.MATNR, 2, 3) AS middle, SUBSTRING(lips.MATNR, 2) AS tail, REPLACE(lips.VRKME, '-', '') AS cleaned, -(lips.LFIMG) AS negative, (lips.LFIMG + COALESCE(lips.LFIMG, 0)) AS periods
FROM QUERY_IT.LIPS AS lips
