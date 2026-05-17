# Database Schema

---

## Overview

The database uses **PostgreSQL 15** with the **pgvector** extension for vector similarity search. All tables live in a single database. Flyway manages migrations.

---

## 1. Entity Relationship Diagram

```
┌──────────────┐       ┌──────────────────┐       ┌───────────────────┐
│    users     │       │      repos       │       │   indexing_jobs   │
├──────────────┤       ├──────────────────┤       ├───────────────────┤
│ id (PK)      │◄──┐   │ id (PK)          │◄──┐   │ id (PK)           │
│ github_id    │   │   │ user_id (FK)     │   │   │ repo_id (FK)      │
│ username     │   │   │ github_repo_id   │   │   │ status            │
│ email        │   │   │ full_name        │   │   │ job_type          │
│ avatar_url   │   │   │ default_branch   │   │   │ progress          │
│ github_token │   │   │ status           │   │   │ total_files       │
│ created_at   │   │   │ last_indexed_at  │   │   │ processed_files   │
│ updated_at   │   │   │ webhook_id       │   │   │ error_message     │
└──────────────┘   │   │ created_at       │   │   │ attempts          │
                   │   │ updated_at       │   │   │ started_at        │
                   │   └──────────────────┘   │   │ completed_at      │
                   │            │              │   │ created_at        │
                   │            │              │   └───────────────────┘
                   │            ▼              │
                   │   ┌──────────────────┐   │
                   │   │   code_chunks    │   │
                   │   ├──────────────────┤   │
                   │   │ id (PK)          │   │
                   │   │ repo_id (FK)     │───┘
                   │   │ file_path        │
                   │   │ start_line       │
                   │   │ end_line         │
                   │   │ chunk_type       │
                   │   │ chunk_name       │
                   │   │ content          │
                   │   │ language         │
                   │   │ embedding (vector)│
                   │   │ token_count      │
                   │   │ created_at       │
                   │   └──────────────────┘
                   │
                   │   ┌──────────────────┐       ┌──────────────────┐
                   │   │  conversations   │       │    messages      │
                   │   ├──────────────────┤       ├──────────────────┤
                   └──►│ id (PK)          │◄──────│ conversation_id  │
                       │ user_id (FK)     │       │ id (PK)          │
                       │ repo_id (FK)     │       │ role             │
                       │ title            │       │ content          │
                       │ created_at       │       │ citations (jsonb)│
                       │ updated_at       │       │ token_count      │
                       └──────────────────┘       │ created_at       │
                                                  └──────────────────┘
```

---

## 2. Table Definitions

### 2.1 `users`

Stores authenticated users (from GitHub OAuth).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PK, DEFAULT gen_random_uuid() | Internal user ID |
| `github_id` | `BIGINT` | UNIQUE, NOT NULL | GitHub user ID |
| `username` | `VARCHAR(255)` | NOT NULL | GitHub username |
| `email` | `VARCHAR(255)` | NULLABLE | GitHub email (may be private) |
| `avatar_url` | `VARCHAR(500)` | NULLABLE | GitHub avatar URL |
| `github_token` | `TEXT` | NOT NULL | Encrypted GitHub access token |
| `created_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | Account creation time |
| `updated_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | Last update time |

**Indexes:**
- `idx_users_github_id` ON `github_id` (unique lookup during OAuth)

---

### 2.2 `repos`

Stores connected GitHub repositories.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PK, DEFAULT gen_random_uuid() | Internal repo ID |
| `user_id` | `UUID` | FK → users.id, NOT NULL | Owner of this connection |
| `github_repo_id` | `BIGINT` | NOT NULL | GitHub repository ID |
| `full_name` | `VARCHAR(255)` | NOT NULL | e.g., "octocat/hello-world" |
| `default_branch` | `VARCHAR(100)` | NOT NULL, DEFAULT 'main' | Branch to index |
| `status` | `VARCHAR(50)` | NOT NULL, DEFAULT 'PENDING' | PENDING, INDEXING, READY, FAILED |
| `last_indexed_at` | `TIMESTAMP` | NULLABLE | When indexing last completed |
| `webhook_id` | `BIGINT` | NULLABLE | GitHub webhook ID (for cleanup) |
| `total_chunks` | `INTEGER` | DEFAULT 0 | Number of indexed chunks |
| `created_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | When repo was connected |
| `updated_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | Last update time |

**Indexes:**
- `idx_repos_user_id` ON `user_id` (list user's repos)
- `idx_repos_user_github` ON `(user_id, github_repo_id)` UNIQUE (prevent duplicate connections)

**Status transitions:**
```
PENDING → INDEXING → READY
                  → FAILED → INDEXING (retry)
```

---

### 2.3 `code_chunks`

Stores parsed code chunks with their vector embeddings.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PK, DEFAULT gen_random_uuid() | Chunk ID |
| `repo_id` | `UUID` | FK → repos.id, NOT NULL | Which repo this belongs to |
| `file_path` | `VARCHAR(1000)` | NOT NULL | Relative path, e.g., "src/auth/middleware.ts" |
| `start_line` | `INTEGER` | NOT NULL | Starting line number (1-based) |
| `end_line` | `INTEGER` | NOT NULL | Ending line number (1-based) |
| `chunk_type` | `VARCHAR(50)` | NOT NULL | FUNCTION, CLASS, METHOD, MODULE, BLOCK |
| `chunk_name` | `VARCHAR(255)` | NULLABLE | Name of function/class, e.g., "authenticateUser" |
| `content` | `TEXT` | NOT NULL | The actual code content |
| `language` | `VARCHAR(50)` | NOT NULL | Programming language (java, typescript, python, etc.) |
| `embedding` | `vector(768)` | NOT NULL | Gemini embedding (768 dimensions) |
| `token_count` | `INTEGER` | NOT NULL | Number of tokens in this chunk |
| `created_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | When chunk was indexed |

**Indexes:**
- `idx_chunks_repo_id` ON `repo_id` (filter chunks by repo)
- `idx_chunks_repo_file` ON `(repo_id, file_path)` (delete chunks for specific files during re-index)
- `idx_chunks_embedding` ON `embedding` USING ivfflat (vector similarity search) — see pgvector setup below

---

### 2.4 `conversations`

Stores chat conversations (one per repo per topic).

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PK, DEFAULT gen_random_uuid() | Conversation ID |
| `user_id` | `UUID` | FK → users.id, NOT NULL | Who owns this conversation |
| `repo_id` | `UUID` | FK → repos.id, NOT NULL | Which repo this is about |
| `title` | `VARCHAR(255)` | NOT NULL | Auto-generated from first question |
| `created_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | When conversation started |
| `updated_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | Last message time |

**Indexes:**
- `idx_conversations_user_id` ON `user_id` (list user's conversations)
- `idx_conversations_user_repo` ON `(user_id, repo_id)` (list conversations for a specific repo)

---

### 2.5 `messages`

Stores individual messages within conversations.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PK, DEFAULT gen_random_uuid() | Message ID |
| `conversation_id` | `UUID` | FK → conversations.id, NOT NULL | Parent conversation |
| `role` | `VARCHAR(20)` | NOT NULL | 'user' or 'assistant' |
| `content` | `TEXT` | NOT NULL | Message text (markdown for assistant) |
| `citations` | `JSONB` | NULLABLE | Array of code citations (see below) |
| `token_count` | `INTEGER` | DEFAULT 0 | Tokens used for this message |
| `created_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | When message was sent |

**Citations JSONB structure:**
```json
[
  {
    "filePath": "src/auth/middleware.ts",
    "startLine": 15,
    "endLine": 42,
    "chunkId": "uuid-of-chunk",
    "snippet": "function authenticateUser(req, res, next) { ... }"
  }
]
```

**Indexes:**
- `idx_messages_conversation_id` ON `conversation_id` (get messages for a conversation)
- `idx_messages_conversation_created` ON `(conversation_id, created_at)` (ordered message retrieval)

---

### 2.6 `indexing_jobs`

Tracks async indexing job status.

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PK, DEFAULT gen_random_uuid() | Job ID |
| `repo_id` | `UUID` | FK → repos.id, NOT NULL | Which repo is being indexed |
| `status` | `VARCHAR(50)` | NOT NULL, DEFAULT 'QUEUED' | QUEUED, PROCESSING, COMPLETED, FAILED |
| `job_type` | `VARCHAR(50)` | NOT NULL, DEFAULT 'FULL' | FULL or INCREMENTAL |
| `progress` | `INTEGER` | DEFAULT 0 | Percentage complete (0-100) |
| `total_files` | `INTEGER` | DEFAULT 0 | Total files to process |
| `processed_files` | `INTEGER` | DEFAULT 0 | Files processed so far |
| `error_message` | `TEXT` | NULLABLE | Error details if failed |
| `attempts` | `INTEGER` | DEFAULT 0 | Number of processing attempts |
| `changed_files` | `JSONB` | NULLABLE | List of files for incremental indexing |
| `started_at` | `TIMESTAMP` | NULLABLE | When processing began |
| `completed_at` | `TIMESTAMP` | NULLABLE | When processing finished |
| `created_at` | `TIMESTAMP` | NOT NULL, DEFAULT NOW() | When job was created |

**Indexes:**
- `idx_jobs_repo_id` ON `repo_id` (find jobs for a repo)
- `idx_jobs_status` ON `status` (find pending/stuck jobs)

**Status transitions:**
```
QUEUED → PROCESSING → COMPLETED
                   → FAILED (after max retries)
FAILED → QUEUED (manual retry)
```

---

## 3. Flyway Migrations

### V1__initial_schema.sql

```sql
-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    github_id BIGINT UNIQUE NOT NULL,
    username VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    avatar_url VARCHAR(500),
    github_token TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_github_id ON users(github_id);

-- Repos table
CREATE TABLE repos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    github_repo_id BIGINT NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    default_branch VARCHAR(100) NOT NULL DEFAULT 'main',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    last_indexed_at TIMESTAMP,
    webhook_id BIGINT,
    total_chunks INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, github_repo_id)
);

CREATE INDEX idx_repos_user_id ON repos(user_id);

-- Conversations table
CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conversations_user_id ON conversations(user_id);
CREATE INDEX idx_conversations_user_repo ON conversations(user_id, repo_id);

-- Messages table
CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    citations JSONB,
    token_count INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX idx_messages_conversation_created ON messages(conversation_id, created_at);
```

### V2__pgvector_setup.sql

```sql
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
```

### V3__indexing_jobs.sql

```sql
-- Indexing jobs table
CREATE TABLE indexing_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id UUID NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
    job_type VARCHAR(50) NOT NULL DEFAULT 'FULL',
    progress INTEGER DEFAULT 0,
    total_files INTEGER DEFAULT 0,
    processed_files INTEGER DEFAULT 0,
    error_message TEXT,
    attempts INTEGER DEFAULT 0,
    changed_files JSONB,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_jobs_repo_id ON indexing_jobs(repo_id);
CREATE INDEX idx_jobs_status ON indexing_jobs(status);
```

---

## 4. pgvector Usage

### Similarity Search Query

This is the core query used by `QueryService` to find relevant code chunks:

```sql
SELECT
    id,
    file_path,
    start_line,
    end_line,
    chunk_type,
    chunk_name,
    content,
    language,
    1 - (embedding <=> :queryEmbedding) AS similarity
FROM code_chunks
WHERE repo_id = :repoId
ORDER BY embedding <=> :queryEmbedding
LIMIT :topK;
```

**Explanation:**
- `<=>` is the cosine distance operator in pgvector
- `1 - distance` gives cosine similarity (0 to 1, higher = more similar)
- `:queryEmbedding` is the embedded user question (768-dim vector)
- `:topK` is typically 8 (retrieve top 8 most relevant chunks)

### Spring Data JPA Custom Query

In `ChunkRepository.java`:

```java
@Query(value = """
    SELECT c.id, c.file_path, c.start_line, c.end_line,
           c.chunk_type, c.chunk_name, c.content, c.language,
           1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
    FROM code_chunks c
    WHERE c.repo_id = :repoId
    ORDER BY c.embedding <=> CAST(:embedding AS vector)
    LIMIT :limit
    """, nativeQuery = true)
List<Object[]> findSimilarChunks(
    @Param("repoId") UUID repoId,
    @Param("embedding") String embedding,
    @Param("limit") int limit
);
```

**Note:** The embedding is passed as a string representation `"[0.1, 0.2, ...]"` and cast to `vector` type in the query.

---

## 5. Data Retention & Cleanup

| Data | Retention | Cleanup Method |
|------|-----------|----------------|
| Code chunks | Until repo is disconnected or re-indexed | CASCADE delete on repo disconnect; delete by file_path on incremental re-index |
| Conversations | Indefinite (user can delete) | CASCADE delete on user/repo delete |
| Indexing jobs | 30 days | Scheduled task deletes completed jobs older than 30 days |
| Users | Indefinite | Manual deletion |

---

## 6. Database Connection Configuration

### application.yml (Spring Boot)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
      idle-timeout: 600000
  jpa:
    hibernate:
      ddl-auto: validate  # Flyway handles schema
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
```

---

## 7. Important Notes for Implementation

1. **pgvector extension must be enabled** on the PostgreSQL instance. On Neon, it's available by default. On RDS, enable it via parameter group.

2. **Embedding dimension is 768** — this matches Google Gemini's `text-embedding-004` model output. If you switch embedding models, update the `vector(768)` column definition.

3. **IVFFlat index** should only be created after you have at least 1000+ chunks. For development, exact search (no index) is fine and simpler.

4. **GitHub token encryption** — in a production app, encrypt `github_token` before storing. For this portfolio project, storing it as plain text in the DB is acceptable (the DB itself is access-controlled). Mention in interviews that you'd use AES-256 encryption in production.

5. **JSONB for citations** — using JSONB allows flexible citation structures without a separate table. This is a deliberate denormalization for read performance (citations are always read with their message).

6. **CASCADE deletes** — when a user disconnects a repo, all chunks, conversations, messages, and jobs for that repo are automatically deleted. This keeps the database clean.
