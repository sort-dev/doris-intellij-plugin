CREATE TABLE t (
    id INT NOT NULL AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    created_at DATETIME,
    PRIMARY KEY (id),
    INDEX idx_name (name)
);
