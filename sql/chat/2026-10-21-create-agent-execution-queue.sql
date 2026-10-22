CREATE TABLE agent_execution_session_sequence (
    session_id UUID PRIMARY KEY,
    next_sequence BIGINT NOT NULL,
    CONSTRAINT chk_agent_execution_sequence_next CHECK (next_sequence >= 1),
    CONSTRAINT fk_agent_execution_sequence_session
        FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
);

CREATE TABLE agent_execution_turn_message (
    chat_message_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    CONSTRAINT chk_agent_execution_turn_message_sequence CHECK (sequence_no >= 0),
    CONSTRAINT fk_agent_execution_turn_message_chat_message
        FOREIGN KEY (chat_message_id) REFERENCES chat_message(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_execution_turn_message_session
        FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_execution_turn_message_session_sequence
    ON agent_execution_turn_message (session_id, sequence_no, chat_message_id);

CREATE TABLE agent_execution_task (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    user_id TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    user_message_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failure_type TEXT,
    CONSTRAINT uq_agent_execution_task_session_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT chk_agent_execution_task_sequence CHECK (sequence_no >= 0),
    CONSTRAINT chk_agent_execution_task_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_agent_execution_task_attempts CHECK (attempt_count >= 0),
    CONSTRAINT fk_agent_execution_task_session
        FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_execution_task_user_message
        FOREIGN KEY (user_message_id) REFERENCES chat_message(id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_execution_task_runnable
    ON agent_execution_task (created_at, id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_agent_execution_task_session_sequence
    ON agent_execution_task (session_id, sequence_no)
    WHERE status IN ('PENDING', 'RUNNING');
