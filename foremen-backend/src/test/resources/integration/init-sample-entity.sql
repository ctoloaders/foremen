-- Create the sample_entity table for integration tests
CREATE TABLE IF NOT EXISTS sample_entity (
    id              BIGSERIAL PRIMARY KEY,
    name_ru         VARCHAR(255),
    name_pl         VARCHAR(255),
    status          VARCHAR(50),
    code            VARCHAR(255),
    city            VARCHAR(255),
    country         VARCHAR(255),
    age             INTEGER,
    price           NUMERIC(10, 2),
    deleted         BOOLEAN DEFAULT FALSE,
    created_date    TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    updated_date    TIMESTAMP DEFAULT NOW(),
    updated_by      VARCHAR(255)
);

-- Create the audit_log table (required by the application context)
CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_class    VARCHAR(255) NOT NULL,
    entity_id       BIGINT,
    operation       VARCHAR(50) NOT NULL,
    performed_by    VARCHAR(255) NOT NULL,
    performed_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    snapshot_before JSONB,
    snapshot_after  JSONB,
    created_date    TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(255),
    updated_date    TIMESTAMP DEFAULT NOW(),
    updated_by      VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_audit_log_entity ON audit_log (entity_class, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_performed_at ON audit_log (performed_at);
