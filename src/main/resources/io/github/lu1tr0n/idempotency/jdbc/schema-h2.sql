-- H2 schema for in-process integration tests. The application code targets
-- PostgreSQL semantics (JSONB, BYTEA, TIMESTAMP WITH TIME ZONE); the
-- equivalent H2 types are below. The JDBC store implementation uses ANSI
-- SQL where possible to keep this delta small.

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_key       VARCHAR(255)  PRIMARY KEY,
    payload_hash          VARCHAR(64),
    http_status           INTEGER       NOT NULL,
    response_headers      TEXT          NOT NULL DEFAULT '{}',
    response_body         VARBINARY     NOT NULL,
    response_content_type VARCHAR(255),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    lock_token            VARCHAR(64),
    locked_until          TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_idempotency_records_expires_at
    ON idempotency_records (expires_at);
