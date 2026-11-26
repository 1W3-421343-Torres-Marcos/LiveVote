CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE polls (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ
);

CREATE INDEX idx_polls_owner_id ON polls (owner_id);

CREATE TABLE poll_options (
    id UUID PRIMARY KEY,
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text VARCHAR(200) NOT NULL,
    display_order INT NOT NULL
);

CREATE INDEX idx_poll_options_poll_id ON poll_options (poll_id);

CREATE TABLE votes (
    id UUID PRIMARY KEY,
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    option_id UUID NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
    device_id VARCHAR(100) NOT NULL,
    cast_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_votes_poll_device UNIQUE (poll_id, device_id)
);

CREATE INDEX idx_votes_poll_id ON votes (poll_id);
