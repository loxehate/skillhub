-- V39__ticket_system.sql

CREATE TABLE team (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    owner_id VARCHAR(128) NOT NULL REFERENCES user_account(id),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_team_namespace_id ON team(namespace_id);
CREATE INDEX idx_team_owner_id ON team(owner_id);

CREATE TABLE team_member (
    id BIGSERIAL PRIMARY KEY,
    team_id BIGINT NOT NULL REFERENCES team(id) ON DELETE CASCADE,
    user_id VARCHAR(128) NOT NULL REFERENCES user_account(id),
    role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'DEV')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(team_id, user_id)
);

CREATE INDEX idx_team_member_team_id ON team_member(team_id);
CREATE INDEX idx_team_member_user_id ON team_member(user_id);

CREATE TABLE ticket (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('BOUNTY', 'ASSIGN')),
    reward NUMERIC(12, 2),
    status VARCHAR(20) NOT NULL,
    creator_id VARCHAR(128) NOT NULL REFERENCES user_account(id),
    namespace_id BIGINT NOT NULL REFERENCES namespace(id),
    target_team_id BIGINT REFERENCES team(id),
    target_user_id VARCHAR(128) REFERENCES user_account(id),
    submit_skill_id BIGINT REFERENCES skill(id),
    submit_skill_version_id BIGINT REFERENCES skill_version(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ticket_namespace_id ON ticket(namespace_id);
CREATE INDEX idx_ticket_creator_id ON ticket(creator_id);
CREATE INDEX idx_ticket_status ON ticket(status);
CREATE INDEX idx_ticket_submit_skill_id ON ticket(submit_skill_id);
CREATE INDEX idx_ticket_submit_skill_version_id ON ticket(submit_skill_version_id);

CREATE TABLE ticket_claim (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    user_id VARCHAR(128) REFERENCES user_account(id),
    team_id BIGINT REFERENCES team(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('APPLIED', 'ACCEPTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ticket_claim_ticket_id ON ticket_claim(ticket_id);
CREATE INDEX idx_ticket_claim_user_id ON ticket_claim(user_id);
CREATE INDEX idx_ticket_claim_team_id ON ticket_claim(team_id);

INSERT INTO permission (code, name, group_code) VALUES
('ticket:create', 'Create tickets', 'ticket'),
('ticket:view', 'View tickets', 'ticket'),
('ticket:claim', 'Claim tickets', 'ticket'),
('ticket:assign', 'Assign tickets', 'ticket'),
('ticket:develop', 'Develop tickets', 'ticket'),
('ticket:review', 'Review tickets', 'ticket'),
('ticket:reject', 'Reject tickets', 'ticket'),
('ticket:submit_skill', 'Submit ticket skills', 'ticket'),
('ticket:manage', 'Manage tickets', 'ticket'),
('ticket:arbitrate', 'Arbitrate tickets', 'ticket')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'SKILL_ADMIN' AND p.code IN ('ticket:view', 'ticket:arbitrate')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'USER_ADMIN' AND p.code IN ('ticket:create', 'ticket:assign')
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r, permission p
WHERE r.code = 'AUDITOR' AND p.code IN ('ticket:view')
ON CONFLICT DO NOTHING;
