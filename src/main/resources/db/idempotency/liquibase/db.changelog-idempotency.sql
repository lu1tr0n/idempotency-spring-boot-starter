--liquibase formatted sql

--changeset idempotency-spring-boot-starter:idempotency-records-001 dbms:postgresql
--comment Create idempotency_records table for the idempotency-spring-boot-starter JDBC store. Not on the default master path; include this file from your master changelog. Never edit after release (checksummed) — ship changes as a new changeset id. Table name hardcoded to the default; rename if you override spring.idempotency.jdbc.table-name.
CREATE TABLE idempotency_records (
    idempotency_key       VARCHAR(255) PRIMARY KEY,
    payload_hash          VARCHAR(64),
    http_status           INTEGER NOT NULL,
    response_headers      TEXT NOT NULL DEFAULT '{}',
    response_body         BYTEA NOT NULL,
    response_content_type VARCHAR(255),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    lock_token            VARCHAR(64),
    locked_until          TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_idempotency_records_expires_at ON idempotency_records (expires_at);
--rollback DROP TABLE idempotency_records;
