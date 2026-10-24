-- parameters: ["D1","new","1"]
INSERT INTO `QUERY_IT`.`W_DOC` (`ID`, `TITLE`, `VERSION`) VALUES (?, ?, ?) AS `new`
ON DUPLICATE KEY UPDATE `TITLE` = `new`.`TITLE`
