BEGIN;

CREATE TABLE IF NOT EXISTS public.document_asset_extraction (
    extraction_id  UUID           PRIMARY KEY,
    asset_id       UUID           NOT NULL,
    document_id    UUID           NOT NULL,
    caption        TEXT,
    ocr_text       TEXT,
    summary        TEXT,
    visual_kind    VARCHAR(32)    NOT NULL,
    model_name     VARCHAR(128)   NOT NULL,
    model_version  VARCHAR(128)   NOT NULL DEFAULT '',
    prompt_version VARCHAR(64)    NOT NULL,
    parser_version VARCHAR(64)    NOT NULL,
    attempt_count  INTEGER        NOT NULL DEFAULT 0,
    confidence     DOUBLE PRECISION,
    raw_result     JSONB,
    status         VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_document_asset_extraction_asset
        FOREIGN KEY (asset_id, document_id)
            REFERENCES public.document_asset(asset_id, document_id) ON DELETE CASCADE,
    CONSTRAINT chk_document_asset_extraction_visual_kind CHECK (
        visual_kind IN ('DIAGRAM', 'CHART', 'TABLE_IMAGE', 'SCREENSHOT', 'TEXT_IMAGE', 'OTHER')
    ),
    CONSTRAINT chk_document_asset_extraction_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'FAILED', 'SUPERSEDED')
    ),
    CONSTRAINT chk_document_asset_extraction_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    ),
    CONSTRAINT chk_document_asset_extraction_attempt CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_asset_extraction_active
    ON public.document_asset_extraction(asset_id)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS public.document_asset_entity (
    entity_id     UUID            PRIMARY KEY,
    extraction_id UUID            NOT NULL,
    document_id   UUID            NOT NULL,
    asset_id      UUID            NOT NULL,
    entity_key    VARCHAR(128)    NOT NULL,
    name          VARCHAR(255)    NOT NULL,
    entity_type   VARCHAR(32)     NOT NULL,
    aliases       JSONB,
    attributes    JSONB,
    bbox          DOUBLE PRECISION[],
    confidence    DOUBLE PRECISION,
    CONSTRAINT fk_document_asset_entity_extraction
        FOREIGN KEY (extraction_id)
            REFERENCES public.document_asset_extraction(extraction_id) ON DELETE CASCADE,
    CONSTRAINT uq_document_asset_entity_key UNIQUE (extraction_id, entity_key),
    CONSTRAINT chk_document_asset_entity_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE TABLE IF NOT EXISTS public.document_asset_relation (
    relation_id       UUID              PRIMARY KEY,
    extraction_id     UUID              NOT NULL,
    document_id       UUID              NOT NULL,
    asset_id          UUID              NOT NULL,
    subject_entity_id UUID              NOT NULL,
    predicate         VARCHAR(64)       NOT NULL,
    object_entity_id  UUID              NOT NULL,
    evidence_text     TEXT,
    evidence_bbox     DOUBLE PRECISION[],
    attributes        JSONB,
    confidence        DOUBLE PRECISION,
    CONSTRAINT fk_document_asset_relation_extraction
        FOREIGN KEY (extraction_id)
            REFERENCES public.document_asset_extraction(extraction_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_asset_relation_subject
        FOREIGN KEY (subject_entity_id)
            REFERENCES public.document_asset_entity(entity_id) ON DELETE CASCADE,
    CONSTRAINT fk_document_asset_relation_object
        FOREIGN KEY (object_entity_id)
            REFERENCES public.document_asset_entity(entity_id) ON DELETE CASCADE,
    CONSTRAINT uq_document_asset_relation_triple
        UNIQUE (extraction_id, subject_entity_id, predicate, object_entity_id),
    CONSTRAINT chk_document_asset_relation_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE INDEX IF NOT EXISTS idx_document_asset_extraction_asset_id
    ON public.document_asset_extraction(asset_id);
CREATE INDEX IF NOT EXISTS idx_document_asset_extraction_document_id
    ON public.document_asset_extraction(document_id);
CREATE INDEX IF NOT EXISTS idx_document_asset_entity_extraction_id
    ON public.document_asset_entity(extraction_id);
CREATE INDEX IF NOT EXISTS idx_document_asset_relation_extraction_id
    ON public.document_asset_relation(extraction_id);

COMMIT;