-- Run this once against your local MySQL server before starting the app.
-- Hibernate (ddl-auto: update) will create/update all tables automatically
-- on startup, but it needs the *database itself* to already exist.
--
-- Usage:
--   mysql -u root -p < setup-db.sql

CREATE DATABASE IF NOT EXISTS meetingai
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
