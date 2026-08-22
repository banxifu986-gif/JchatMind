BEGIN;

CREATE TABLE document_asset (
    asset_id       UUID         PRIMARY KEY,
    document_id    UUID         NOT NULL,
    asset_type     VARCHAR(32)  NOT NULL,
    asset_key      VARCHAR(160) NOT NULL,
    page_number    INTEGER      DEFAULT NULL,
    locator        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    content_hash   CHAR(64)     NOT NULL,
    parser_version VARCHAR(64)  NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_asset_locator UNIQUE (document_id, asset_type, asset_key),
    CONSTRAINT uq_document_asset_id_document UNIQUE (asset_id, document_id),
    CONSTRAINT fk_document_asset_document
        FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT chk_document_asset_type CHECK (
        asset_type IN ('PDF_PAGE_TEXT', 'IMAGE', 'TABLE', 'FORMULA')
    ),
    CONSTRAINT chk_document_asset_page_number CHECK (
        page_number IS NULL OR page_number > 0
    ),
    CONSTRAINT chk_document_asset_content_hash CHECK (
        content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_document_asset_status CHECK (
        status IN ('PENDING', 'READY', 'FAILED')
    )
);

ALTER TABLE chunk_bge_m3
    ADD CONSTRAINT uq_chunk_bge_m3_id_doc UNIQUE (id, doc_id);

CREATE TABLE document_asset_chunk (
    asset_id          UUID NOT NULL,
    chunk_id          UUID NOT NULL,
    asset_document_id UUID NOT NULL,
    chunk_document_id UUID NOT NULL,
    PRIMARY KEY (asset_id, chunk_id),
    CONSTRAINT chk_document_asset_chunk_same_document CHECK (
        asset_document_id = chunk_document_id
    ),
    CONSTRAINT fk_document_asset_chunk_asset
        FOREIGN KEY (asset_id, asset_document_id)
            REFERENCES document_asset(asset_id, document_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_asset_chunk_chunk
        FOREIGN KEY (chunk_id, chunk_document_id)
            REFERENCES chunk_bge_m3(id, doc_id) ON DELETE CASCADE
);

CREATE INDEX idx_document_asset_document_id
    ON document_asset(document_id);
CREATE INDEX idx_document_asset_chunk_chunk_id
    ON document_asset_chunk(chunk_id);

COMMIT;
