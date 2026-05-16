-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Code chunks table with vector column
CREATE TABLE code_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    file_path VARCHAR(1000) NOT NULL,
    start_line INTEGER NOT NULL,
    end_line INTEGER NOT NULL,
    chunk_type VARCHAR(50) NOT NULL,
    chunk_name VARCHAR(255),
    content TEXT NOT NULL,
    language VARCHAR(50) NOT NULL,
    embedding vector(768) NOT NULL,
    token_count INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chunks_repo_id ON code_chunks(repo_id);
CREATE INDEX idx_chunks_repo_file ON code_chunks(repo_id, file_path);

-- IVFFlat index for vector similarity search
-- NOTE: This index should be created AFTER inserting initial data
-- for better clustering. For small datasets (<10K rows), exact search is fine.
-- Create with: CREATE INDEX idx_chunks_embedding ON code_chunks
--              USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
--
-- For initial development, use exact search (no index needed).
-- Add the IVFFlat index when you have >1000 chunks.
