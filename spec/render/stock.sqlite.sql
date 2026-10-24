-- parameters: ["2026","2026"]
SELECT mardh.MATNR AS material, mardh.LFGJA AS year, mardh.LFMON AS period, COALESCE(mardh.LABST, 0) AS quantity
FROM QUERY_IT.MARDH AS mardh
WHERE mardh.LFGJA = ?
UNION ALL
SELECT mchbh.MATNR, mchbh.LFGJA, mchbh.LFMON, COALESCE(mchbh.CLABS, 0)
FROM QUERY_IT.MCHBH AS mchbh
WHERE mchbh.LFGJA = ?
