CREATE TABLE ticket_comment (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    author_id VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ticket_comment_ticket_id ON ticket_comment(ticket_id);
