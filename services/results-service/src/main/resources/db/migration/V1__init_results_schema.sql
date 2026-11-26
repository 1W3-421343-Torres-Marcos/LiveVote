CREATE TABLE poll_projections (
    poll_id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ
);

CREATE TABLE option_counts (
    option_id UUID PRIMARY KEY,
    poll_id UUID NOT NULL REFERENCES poll_projections(poll_id) ON DELETE CASCADE,
    text VARCHAR(200) NOT NULL,
    display_order INT NOT NULL,
    vote_count BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_option_counts_poll_id ON option_counts (poll_id);

CREATE TABLE processed_votes (
    vote_id UUID PRIMARY KEY,
    poll_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
