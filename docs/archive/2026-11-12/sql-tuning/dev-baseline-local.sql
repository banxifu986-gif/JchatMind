
            CREATE EXTENSION vector;

            CREATE TABLE knowledge_base (
                id UUID PRIMARY KEY,
                name VARCHAR(128) NOT NULL,
                description VARCHAR(255),
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE agent (
                id UUID PRIMARY KEY,
                user_id BIGINT NOT NULL,
                name VARCHAR(128) NOT NULL,
                description VARCHAR(255),
                system_prompt TEXT,
                model VARCHAR(128) NOT NULL,
                allowed_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
                allowed_kbs JSONB NOT NULL DEFAULT '[]'::jsonb,
                chat_options JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE chat_session (
                id UUID PRIMARY KEY,
                user_id TEXT NOT NULL,
                agent_id UUID,
                title TEXT,
                metadata JSONB,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE chat_message (
                id UUID PRIMARY KEY,
                session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
                role VARCHAR(32) NOT NULL,
                content TEXT,
                metadata JSONB,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE document (
                id UUID PRIMARY KEY,
                kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
                filename VARCHAR(255) NOT NULL,
                filetype VARCHAR(32),
                size BIGINT,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE chunk_bge_m3 (
                id UUID PRIMARY KEY,
                kb_id UUID,
                doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
                content TEXT,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                embedding vector(3),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE user_memory (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id VARCHAR(64) NOT NULL,
                session_id UUID,
                memory_type VARCHAR(32),
                content TEXT NOT NULL,
                embedding vector(3),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE user_memory_candidate (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id VARCHAR(64) NOT NULL,
                session_id UUID,
                memory_type VARCHAR(32),
                content TEXT NOT NULL,
                evidence TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            
