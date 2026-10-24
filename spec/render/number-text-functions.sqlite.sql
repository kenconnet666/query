-- parameters: ["D1"]
SELECT CASE WHEN length(lips.MATNR) >= 5 THEN substr(lips.MATNR, 1, 5) ELSE substr(replace(hex(zeroblob(5)), '00', '0'), 1, 5 - length(lips.MATNR)) || lips.MATNR END AS padded, CASE WHEN length(lips.VRKME) >= 4 THEN substr(lips.VRKME, 1, 4) ELSE lips.VRKME || substr(replace(hex(zeroblob(4)), '00', '*'), 1, 4 - length(lips.VRKME)) END AS unit, mod(lips.LFIMG, 2) AS remainder, power(lips.LFIMG, 2) AS squared, sqrt(lips.LFIMG) AS root, SIGN((lips.LFIMG - 4)) AS sign
FROM QUERY_IT.LIPS AS lips
WHERE lips.VBELN = ?
