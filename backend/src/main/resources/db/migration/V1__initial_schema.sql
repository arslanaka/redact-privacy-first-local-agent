CREATE TABLE documents (
    id                   UUID PRIMARY KEY,
    original_filename    VARCHAR(255) NOT NULL,
    content_type         VARCHAR(100) NOT NULL,
    format               VARCHAR(20)  NOT NULL,
    size_bytes           BIGINT       NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    current_stage        VARCHAR(40),
    -- Internal storage keys (UUID-based file names). Never exposed through the API.
    storage_key          VARCHAR(100) NOT NULL,
    redacted_storage_key VARCHAR(100),
    extracted_text       TEXT,
    analysis_summary     JSONB,
    redaction_report     JSONB,
    model_name           VARCHAR(100),
    model_is_mock        BOOLEAN,
    error_code           VARCHAR(50),
    error_message        VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_documents_created_at ON documents (created_at);

CREATE TABLE detected_entities (
    id                    UUID PRIMARY KEY,
    document_id           UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    text                  TEXT         NOT NULL,
    type                  VARCHAR(30)  NOT NULL,
    confidence            DOUBLE PRECISION NOT NULL,
    start_offset          INTEGER      NOT NULL,
    end_offset            INTEGER      NOT NULL,
    source                VARCHAR(20)  NOT NULL,
    review_status         VARCHAR(20)  NOT NULL,
    recommendation        VARCHAR(20)  NOT NULL,
    recommendation_reason VARCHAR(300) NOT NULL,
    regex_confidence      DOUBLE PRECISION,
    model_confidence      DOUBLE PRECISION,
    model_labels          VARCHAR(300),
    detector_note         VARCHAR(300),
    created_at            TIMESTAMPTZ  NOT NULL,
    reviewed_at           TIMESTAMPTZ,
    CONSTRAINT chk_offsets CHECK (start_offset >= 0 AND end_offset > start_offset),
    CONSTRAINT chk_confidence CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE INDEX idx_entities_document ON detected_entities (document_id, start_offset);

-- Deliberately no foreign key: the DOCUMENT_DELETED / DOCUMENT_EXPIRED events must outlive the document.
CREATE TABLE audit_events (
    id          UUID PRIMARY KEY,
    document_id UUID,
    event_type  VARCHAR(40)  NOT NULL,
    message     VARCHAR(500) NOT NULL,
    metadata    JSONB,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_audit_document ON audit_events (document_id, created_at);
