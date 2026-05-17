# System Architecture — Detailed Design

---

## 1. High-Level Architecture

The system follows a **layered architecture** with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────┐
│                        CLIENT LAYER                              │
│                                                                 │
│  React SPA (Vercel) ──── HTTPS/SSE ────► Spring Boot API        │
└─────────────────────────────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│                      APPLICATION LAYER                           │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌────────────────────────┐  │
│  │ Controllers │  │ Middleware  │  │ SSE Emitter Manager    │  │
│  │ (REST)      │  │ (Auth,Rate) │  │ (Streaming responses)  │  │
│  └──────┬──────┘  └──────┬──────┘  └────────────┬───────────┘  │
│         │                 │                      │              │
│  ┌──────▼─────────────────▼──────────────────────▼───────────┐  │
│  │                   SERVICE LAYER                            │  │
│  │                                                           │  │
│  │  AuthService │ RepoService │ IndexingService │ QuerySvc   │  │
│  │  ConversationService │ EmbeddingService │ ChunkingService │  │
│  │  CacheService │ RateLimitService │ GeminiService          │  │
│  └──────┬────────────────────────────────────────────────────┘  │
│         │                                                       │
│  ┌──────▼────────────────────────────────────────────────────┐  │
│  │                 DATA ACCESS LAYER                          │  │
│  │                                                           │  │
│  │  Spring Data JPA Repositories + Custom pgvector queries   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────┐
│                     INFRASTRUCTURE LAYER                         │
│                                                                 │
│  PostgreSQL + pgvector │ Upstash Redis │ AWS SQS │ Gemini API   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Breakdown

### 2.1 Controllers (REST API Layer)

Each controller handles a specific domain. They are thin — only request validation, calling services, and formatting responses.

| Controller | Responsibility |
|-----------|---------------|
| `AuthController` | GitHub OAuth callback, token exchange, get current user, logout |
| `RepoController` | Connect repo, disconnect repo, list repos, get indexing status, webhook receiver |
| `QueryController` | Ask a question (SSE streaming endpoint), get cached answer |
| `ConversationController` | Create conversation, list conversations, get messages, delete conversation |

### 2.2 Service Layer (Business Logic)

| Service | Responsibility | Dependencies |
|---------|---------------|--------------|
| `AuthService` | Exchange GitHub code for token, fetch user profile, create/update user in DB | GitHub API, UserRepository |
| `RepoService` | Validate repo access, store repo metadata, trigger indexing, handle webhooks | GitHub API, RepoRepository, SQS |
| `IndexingService` | Clone repo, walk files, invoke chunking, generate embeddings, store chunks | JGit, ChunkingService, EmbeddingService, ChunkRepository |
| `ChunkingService` | Parse code files using tree-sitter, split at function/class boundaries | tree-sitter (process execution) |
| `EmbeddingService` | Call Gemini embedding API, batch processing, retry logic | Gemini API, CircuitBreaker |
| `QueryService` | Orchestrate RAG pipeline: embed query → vector search → build prompt → stream LLM | EmbeddingService, ChunkRepository, GeminiService, CacheService |
| `ConversationService` | CRUD for conversations and messages, manage conversation context window | ConversationRepository, MessageRepository |
| `CacheService` | Cache query results in Redis, cache invalidation on re-index | Redis (Upstash) |
| `RateLimitService` | Token bucket per user, check and consume tokens | Redis (Upstash) |
| `GeminiService` | Call Gemini chat API with streaming, handle SSE response parsing | Gemini API, CircuitBreaker |

### 2.3 Worker Layer (Async Processing)

| Component | Responsibility |
|-----------|---------------|
| `IndexingWorker` | Listens to SQS queue, processes indexing jobs, updates job status |
| `StaleJobCleanup` | Scheduled task (@Scheduled) — finds jobs stuck in "PROCESSING" for >10 min, marks as FAILED |

### 2.4 Infrastructure Components

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Primary Database | PostgreSQL 15 (RDS/Neon) | Users, repos, conversations, messages, jobs |
| Vector Store | pgvector extension | Code chunk embeddings + similarity search |
| Message Queue | AWS SQS | Decouple indexing from API requests |
| Dead Letter Queue | AWS SQS (DLQ) | Failed indexing jobs for manual review |
| Cache | Upstash Redis | Query result caching + rate limit counters |
| LLM Provider | Google Gemini API | Text generation + embeddings |
| Git Operations | JGit | Clone repos, compute diffs |
| AST Parser | tree-sitter CLI | Parse code into AST for intelligent chunking |

---

## 3. Data Flow Diagrams

### 3.1 User Authentication Flow

```
┌────────┐     ┌──────────┐     ┌────────────┐     ┌────────┐
│Frontend│     │ Backend  │     │  GitHub    │     │   DB   │
└───┬────┘     └────┬─────┘     └─────┬──────┘     └───┬────┘
    │               │                  │                │
    │ Click "Login  │                  │                │
    │ with GitHub"  │                  │                │
    │──────────────►│                  │                │
    │               │                  │                │
    │ Redirect to   │                  │                │
    │ GitHub OAuth  │                  │                │
    │◄──────────────│                  │                │
    │               │                  │                │
    │ User authorizes on GitHub        │                │
    │─────────────────────────────────►│                │
    │               │                  │                │
    │ Redirect back │with ?code=xxx    │                │
    │──────────────►│                  │                │
    │               │                  │                │
    │               │ POST /login/     │                │
    │               │ oauth/access_token                │
    │               │─────────────────►│                │
    │               │                  │                │
    │               │ access_token     │                │
    │               │◄─────────────────│                │
    │               │                  │                │
    │               │ GET /user        │                │
    │               │─────────────────►│                │
    │               │                  │                │
    │               │ user profile     │                │
    │               │◄─────────────────│                │
    │               │                  │                │
    │               │ Upsert user      │                │
    │               │─────────────────────────────────►│
    │               │                  │                │
    │               │ Generate JWT     │                │
    │               │                  │                │
    │ Return JWT +  │                  │                │
    │ user info     │                  │                │
    │◄──────────────│                  │                │
    │               │                  │                │
    │ Store JWT in  │                  │                │
    │ localStorage  │                  │                │
    └───────────────┴──────────────────┴────────────────┘
```

### 3.2 Repository Indexing Flow

```
┌────────┐   ┌──────────┐   ┌─────┐   ┌────────┐   ┌───────┐   ┌──────┐
│Frontend│   │Controller│   │ SQS │   │ Worker │   │Gemini │   │  DB  │
└───┬────┘   └────┬─────┘   └──┬──┘   └───┬────┘   └───┬───┘   └──┬───┘
    │              │            │           │            │           │
    │ POST /repos  │            │           │            │           │
    │ {repoUrl}    │            │           │            │           │
    │─────────────►│            │           │            │           │
    │              │            │           │            │           │
    │              │ Validate repo access    │            │           │
    │              │ (GitHub API)            │            │           │
    │              │            │           │            │           │
    │              │ Save repo (status=PENDING)          │           │
    │              │────────────────────────────────────────────────►│
    │              │            │           │            │           │
    │              │ Create IndexingJob (status=QUEUED)  │           │
    │              │────────────────────────────────────────────────►│
    │              │            │           │            │           │
    │              │ Send message│           │            │           │
    │              │ (jobId,     │           │            │           │
    │              │  repoId)    │           │            │           │
    │              │────────────►│           │            │           │
    │              │            │           │            │           │
    │ 202 Accepted │            │           │            │           │
    │ {jobId}      │            │           │            │           │
    │◄─────────────│            │           │            │           │
    │              │            │           │            │           │
    │              │            │ Poll msg  │            │           │
    │              │            │◄──────────│            │           │
    │              │            │           │            │           │
    │              │            │           │ Update job: PROCESSING │
    │              │            │           │───────────────────────►│
    │              │            │           │            │           │
    │              │            │           │ 1. Clone repo (JGit)   │
    │              │            │           │            │           │
    │              │            │           │ 2. Walk files, filter  │
    │              │            │           │    (skip binaries,     │
    │              │            │           │     node_modules, etc) │
    │              │            │           │            │           │
    │              │            │           │ 3. Parse each file     │
    │              │            │           │    (tree-sitter AST)   │
    │              │            │           │            │           │
    │              │            │           │ 4. Chunk at function/  │
    │              │            │           │    class boundaries    │
    │              │            │           │            │           │
    │              │            │           │ 5. Batch embed chunks  │
    │              │            │           │────────────►           │
    │              │            │           │            │           │
    │              │            │           │ embeddings │           │
    │              │            │           │◄───────────│           │
    │              │            │           │            │           │
    │              │            │           │ 6. Store chunks +      │
    │              │            │           │    embeddings          │
    │              │            │           │───────────────────────►│
    │              │            │           │            │           │
    │              │            │           │ 7. Update job:         │
    │              │            │           │    COMPLETED           │
    │              │            │           │───────────────────────►│
    │              │            │           │            │           │
    │ SSE: indexing│            │           │            │           │
    │ complete     │            │           │            │           │
    │◄─────────────────────────────────────│            │           │
    └──────────────┴────────────┴───────────┴────────────┴───────────┘
```

### 3.3 Question Answering Flow (RAG Pipeline)

```
┌────────┐   ┌──────────┐   ┌───────┐   ┌──────┐   ┌───────┐   ┌──────┐
│Frontend│   │Controller│   │ Cache │   │  DB  │   │Gemini │   │  DB  │
│        │   │          │   │(Redis)│   │(pgvec)│  │  API  │   │(msgs)│
└───┬────┘   └────┬─────┘   └───┬───┘   └──┬───┘   └───┬───┘   └──┬───┘
    │              │             │           │           │           │
    │ POST /query  │             │           │           │           │
    │ {question,   │             │           │           │           │
    │  repoId,     │             │           │           │           │
    │  conversationId}           │           │           │           │
    │─────────────►│             │           │           │           │
    │              │             │           │           │           │
    │              │ 1. Rate limit check     │           │           │
    │              │─────────────►           │           │           │
    │              │ OK / 429    │           │           │           │
    │              │◄────────────│           │           │           │
    │              │             │           │           │           │
    │              │ 2. Check cache          │           │           │
    │              │    hash(repoId+question)│           │           │
    │              │─────────────►           │           │           │
    │              │ miss        │           │           │           │
    │              │◄────────────│           │           │           │
    │              │             │           │           │           │
    │              │ 3. Get conversation history         │           │
    │              │────────────────────────────────────────────────►│
    │              │ last 10 messages        │           │           │
    │              │◄───────────────────────────────────────────────│
    │              │             │           │           │           │
    │              │ 4. Embed the question   │           │           │
    │              │────────────────────────────────────►│           │
    │              │ query_embedding         │           │           │
    │              │◄───────────────────────────────────│           │
    │              │             │           │           │           │
    │              │ 5. Vector similarity search         │           │
    │              │    (top 8 chunks, cosine distance)  │           │
    │              │────────────────────────►│           │           │
    │              │ relevant chunks         │           │           │
    │              │◄───────────────────────│           │           │
    │              │             │           │           │           │
    │              │ 6. Build prompt:        │           │           │
    │              │    system_prompt +      │           │           │
    │              │    retrieved_chunks +   │           │           │
    │              │    conversation_history +           │           │
    │              │    user_question        │           │           │
    │              │             │           │           │           │
    │              │ 7. Stream LLM response  │           │           │
    │              │────────────────────────────────────►│           │
    │              │             │           │           │           │
    │ SSE: token   │             │           │           │           │
    │◄─────────────│◄───────────────────────────────────│           │
    │ SSE: token   │             │           │           │           │
    │◄─────────────│◄───────────────────────────────────│           │
    │ SSE: token   │             │           │           │           │
    │◄─────────────│◄───────────────────────────────────│           │
    │ ...          │             │           │           │           │
    │ SSE: [DONE]  │             │           │           │           │
    │◄─────────────│             │           │           │           │
    │              │             │           │           │           │
    │              │ 8. Save complete answer │           │           │
    │              │────────────────────────────────────────────────►│
    │              │             │           │           │           │
    │              │ 9. Cache result         │           │           │
    │              │─────────────►           │           │           │
    │              │             │           │           │           │
    └──────────────┴─────────────┴───────────┴───────────┴───────────┘
```

### 3.4 Incremental Re-indexing Flow (Webhook)

```
┌────────┐   ┌──────────┐   ┌─────┐   ┌────────┐   ┌──────┐
│ GitHub │   │Controller│   │ SQS │   │ Worker │   │  DB  │
└───┬────┘   └────┬─────┘   └──┬──┘   └───┬────┘   └──┬───┘
    │              │            │           │            │
    │ POST /webhooks/github     │           │            │
    │ (push event) │            │           │            │
    │─────────────►│            │           │            │
    │              │            │           │            │
    │              │ 1. Verify webhook signature         │
    │              │    (HMAC SHA-256)       │           │
    │              │            │           │            │
    │              │ 2. Extract changed files│           │            │
    │              │    from payload         │           │            │
    │              │            │           │            │
    │              │ 3. Enqueue incremental  │           │            │
    │              │    index job            │           │            │
    │              │    {repoId, changedFiles}           │            │
    │              │────────────►│           │            │
    │              │            │           │            │
    │ 200 OK       │            │           │            │
    │◄─────────────│            │           │            │
    │              │            │           │            │
    │              │            │ Poll      │            │
    │              │            │◄──────────│            │
    │              │            │           │            │
    │              │            │           │ 1. Delete old chunks   │
    │              │            │           │    for changed files   │
    │              │            │           │───────────────────────►│
    │              │            │           │            │           │
    │              │            │           │ 2. Re-parse only       │
    │              │            │           │    changed files       │
    │              │            │           │            │           │
    │              │            │           │ 3. Re-embed + store    │
    │              │            │           │───────────────────────►│
    │              │            │           │            │           │
    │              │            │           │ 4. Invalidate cache    │
    │              │            │           │    for this repo       │
    └──────────────┴────────────┴───────────┴────────────┴───────────┘
```

---

## 4. Key Design Decisions

### 4.1 Why pgvector Instead of a Dedicated Vector DB?

| Factor | pgvector | Pinecone/Qdrant |
|--------|----------|-----------------|
| Cost | Free (part of PostgreSQL) | Paid or self-hosted |
| Operational complexity | Zero — same DB for everything | Separate service to manage |
| Performance at our scale | Excellent for <1M vectors | Needed for >10M vectors |
| Joins with relational data | Native SQL joins | Requires separate queries |
| Decision | ✅ Use pgvector | Overkill for this project |

### 4.2 Why SQS Instead of an In-Process Queue?

- Indexing a repo can take **2-10 minutes** (clone + parse + embed)
- If the API server restarts, in-process jobs are lost
- SQS provides **at-least-once delivery**, **DLQ for failures**, and **visibility timeout**
- Demonstrates real distributed systems knowledge in interviews
- AWS Free Tier: 1M requests/month (more than enough)

### 4.3 Why SSE Instead of WebSocket?

| Factor | SSE | WebSocket |
|--------|-----|-----------|
| Direction | Server → Client (one-way) | Bidirectional |
| Our need | Stream tokens to client | We only need one-way |
| Complexity | Simple HTTP, auto-reconnect | Connection management, heartbeats |
| Proxy/CDN support | Works everywhere | Some proxies break WS |
| Spring Boot support | `SseEmitter` built-in | Requires STOMP/raw WS config |
| Decision | ✅ Use SSE | Unnecessary complexity |

### 4.4 Why tree-sitter for Chunking?

Naive chunking (split every 500 tokens) breaks code mid-function, losing context. tree-sitter gives us:

```
Naive chunking:                    AST-aware chunking:
┌─────────────────────┐           ┌─────────────────────┐
│ function auth() {   │           │ function auth() {   │
│   const token =     │           │   const token =     │
│     getToken();     │           │     getToken();     │
│   if (!token) {     │           │   if (!token) {     │
│     throw new       │           │     throw new       │
├─────── CUT ─────────┤           │       Error();      │
│       Error();      │           │   }                 │
│   }                 │           │   return verify(    │
│   return verify(    │           │     token           │
│     token           │           │   );                │
│   );                │           │ }                   │
│ }                   │           └─────────────────────┘  ← Complete function
└─────────────────────┘
                                  ┌─────────────────────┐
│ function payment() {│           │ function payment() {│
│   ...               │           │   ...               │  ← Next function
```

Each chunk = one complete function/class/method with its full context (file path, line numbers, imports).

### 4.5 Why Conversation Memory?

Without memory, every question is independent. With memory:

```
User: "How does authentication work?"
AI: "The auth module in src/auth/middleware.ts uses JWT tokens..."

User: "What about the refresh token logic?"  ← This only makes sense with context
AI: "Building on the auth middleware I mentioned, the refresh logic is in src/auth/refresh.ts..."
```

Implementation: Store last N messages per conversation, include them in the LLM prompt as context.

### 4.6 Circuit Breaker Pattern (Resilience4j)

When Gemini API is down or slow:

```
CLOSED (normal) ──── failures > threshold ────► OPEN (reject all)
       ▲                                              │
       │                                              │ after timeout
       │                                              ▼
       └──────── success ◄──── HALF_OPEN (allow 1 request)
```

Configuration:
- **Failure threshold:** 5 failures in 60 seconds → open circuit
- **Wait duration:** 30 seconds before trying again
- **Fallback:** Return cached result if available, otherwise return "Service temporarily unavailable"

---

## 5. Security Considerations

| Concern | Solution |
|---------|----------|
| API Authentication | JWT tokens (issued after GitHub OAuth) |
| GitHub token storage | Encrypted in DB (AES-256), never sent to frontend |
| Webhook verification | HMAC SHA-256 signature validation |
| Rate limiting | Per-user token bucket (prevents LLM cost abuse) |
| SQL injection | Spring Data JPA parameterized queries |
| CORS | Whitelist only the Vercel frontend domain |
| Secrets | AWS SSM Parameter Store (not in code/env files) |
| Repo access | Verify user has GitHub access before indexing |

---

## 6. Scalability Notes (Interview Talking Points)

While this is a portfolio project, the architecture supports scaling:

| Bottleneck | Current | Scaled Solution |
|-----------|---------|-----------------|
| Indexing throughput | Single worker on EC2 | Multiple EC2 instances consuming from same SQS queue |
| Vector search speed | pgvector (fine for <1M vectors) | Migrate to Qdrant/Pinecone for >10M |
| LLM latency | Single Gemini call | Load balance across multiple providers |
| Cache hit rate | Simple key-value | Semantic cache (embed query, find similar cached queries) |
| Database connections | Single RDS instance | Read replicas for query traffic |

---

## 7. Error Handling Strategy

### API Errors (returned to frontend)
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "You have exceeded the rate limit. Please wait 30 seconds.",
    "retryAfter": 30
  }
}
```

### Error Codes
| Code | HTTP Status | Meaning |
|------|-------------|---------|
| `UNAUTHORIZED` | 401 | Missing or invalid JWT |
| `REPO_NOT_FOUND` | 404 | Repo doesn't exist or user lacks access |
| `REPO_NOT_INDEXED` | 400 | Repo hasn't been indexed yet |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many queries |
| `INDEXING_FAILED` | 500 | Indexing job failed (check DLQ) |
| `LLM_UNAVAILABLE` | 503 | Gemini API down (circuit open) |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

### Indexing Job Error Handling
```
Job fails → Retry (max 3 attempts with exponential backoff)
                → Still fails → Move to DLQ
                → Mark job as FAILED in DB
                → Notify user via SSE: "Indexing failed: {reason}"
```
