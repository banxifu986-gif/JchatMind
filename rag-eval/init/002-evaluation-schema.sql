BEGIN;

CREATE SCHEMA IF NOT EXISTS rag_eval;

CREATE TABLE IF NOT EXISTS rag_eval.evaluation_namespace (
    namespace TEXT PRIMARY KEY,
    CONSTRAINT chk_evaluation_namespace CHECK (namespace = 'rag-eval')
);

INSERT INTO rag_eval.evaluation_namespace (namespace)
VALUES ('rag-eval')
ON CONFLICT (namespace) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    owner_id BIGINT NOT NULL,
    evaluation_namespace TEXT NOT NULL DEFAULT 'rag-eval'
        REFERENCES rag_eval.evaluation_namespace(namespace),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_knowledge_base_evaluation_namespace CHECK (evaluation_namespace = 'rag-eval')
);

CREATE TABLE IF NOT EXISTS public.document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id UUID NOT NULL REFERENCES public.knowledge_base(id) ON DELETE CASCADE,
    filename VARCHAR(1024) NOT NULL,
    filetype VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL CHECK (size >= 0),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    evaluation_namespace TEXT NOT NULL DEFAULT 'rag-eval'
        REFERENCES rag_eval.evaluation_namespace(namespace),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_document_evaluation_namespace CHECK (evaluation_namespace = 'rag-eval')
);

CREATE INDEX IF NOT EXISTS public.idx_document_kb_id ON public.document(kb_id);

CREATE TABLE IF NOT EXISTS public.chunk_bge_m3 (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kb_id UUID NOT NULL REFERENCES public.knowledge_base(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES public.document(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding VECTOR(1024) NOT NULL,
    title_bm25_vector bm25_catalog.bm25vector,
    content_bm25_vector bm25_catalog.bm25vector,
    bm25_index_version INTEGER,
    evaluation_namespace TEXT NOT NULL DEFAULT 'rag-eval'
        REFERENCES rag_eval.evaluation_namespace(namespace),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_chunk_bge_m3_evaluation_namespace CHECK (evaluation_namespace = 'rag-eval'),
    CONSTRAINT chk_chunk_bge_m3_bm25_index_version
        CHECK (bm25_index_version IS NULL OR bm25_index_version > 0),
    CONSTRAINT chk_chunk_bge_m3_bm25_projection_complete
        CHECK (
            (title_bm25_vector IS NULL AND content_bm25_vector IS NULL AND bm25_index_version IS NULL)
            OR
            (title_bm25_vector IS NOT NULL AND content_bm25_vector IS NOT NULL AND bm25_index_version > 0)
        )
);

CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_kb_id ON public.chunk_bge_m3(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_doc_id ON public.chunk_bge_m3(doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_embedding_cosine
    ON public.chunk_bge_m3 USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_title_bm25
    ON public.chunk_bge_m3 USING bm25 (title_bm25_vector bm25_catalog.bm25_ops);
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_content_bm25
    ON public.chunk_bge_m3 USING bm25 (content_bm25_vector bm25_catalog.bm25_ops);

CREATE TABLE IF NOT EXISTS public.rag_bm25_token_dictionary (
    token_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_rag_bm25_token_dictionary_token_not_blank CHECK (token <> '')
);

COMMIT;
