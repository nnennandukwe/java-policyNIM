CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS ${tableName} (
    chunk_id TEXT PRIMARY KEY,
    source_path TEXT NOT NULL,
    section_path TEXT NOT NULL,
    line_range TEXT NOT NULL,
    chunk_text TEXT NOT NULL,
    policy_id TEXT NOT NULL,
    title TEXT NOT NULL,
    doc_type TEXT NOT NULL,
    domain TEXT NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    grounded_in TEXT[] NOT NULL DEFAULT '{}',
    embedding VECTOR,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ${tableName}_policy_id_idx ON ${tableName} (policy_id);
CREATE INDEX IF NOT EXISTS ${tableName}_source_path_idx ON ${tableName} (source_path);
