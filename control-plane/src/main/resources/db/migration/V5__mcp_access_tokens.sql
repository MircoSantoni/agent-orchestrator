CREATE TABLE mcp_access_token (
    id UUID PRIMARY KEY,
    owner_sub TEXT NOT NULL,
    name VARCHAR(80) NOT NULL,
    secret_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX mcp_access_token_owner_idx ON mcp_access_token (owner_sub, created_at DESC);
