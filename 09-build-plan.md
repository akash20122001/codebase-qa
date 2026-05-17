# Build Plan — Sprint Breakdown

---

## Overview

Total estimated time: **3 weeks** (assuming ~3-4 hours/day)

Each sprint has clear deliverables and acceptance criteria so a model (or developer) can verify completion before moving on.

---

## Sprint 1: Foundation (Days 1-5)

### Goal: Backend skeleton + Database + Auth working end-to-end

---

### Task 1.1: Project Initialization

**What to do:**
1. Generate Spring Boot project from Spring Initializr
2. Add all Maven dependencies from `05-backend-guide-part1.md` Section 1.2
3. Create the package structure (`config/`, `controller/`, `service/`, `model/`, `repository/`, `dto/`, `exception/`, `util/`, `worker/`, `middleware/`)
4. Set up `application.yml` with all config properties
5. Create `docker-compose.yml` for local PostgreSQL + Redis

**Acceptance criteria:**
- [ ] `mvn clean compile` succeeds with no errors
- [ ] `docker-compose up -d` starts PostgreSQL (with pgvector) and Redis
- [ ] Application starts with `mvn spring-boot:run` (may fail on missing beans — that's OK at this stage)

---

### Task 1.2: Database Schema + Flyway Migrations

**What to do:**
1. Create `src/main/resources/db/migration/V1__initial_schema.sql` (from `03-database-schema.md`)
2. Create `V2__pgvector_setup.sql`
3. Create `V3__indexing_jobs.sql`
4. Create all JPA entities: `User`, `Repo`, `CodeChunk`, `Conversation`, `Message`, `IndexingJob`
5. Create all repository interfaces

**Acceptance criteria:**
- [ ] Application starts and Flyway runs all 3 migrations successfully
- [ ] All tables exist in PostgreSQL (verify with `\dt` in psql)
- [ ] pgvector extension is enabled (`SELECT * FROM pg_extension WHERE extname = 'vector'`)

---

### Task 1.3: Authentication (GitHub OAuth + JWT)

**What to do:**
1. Implement `JwtService.java`
2. Implement `JwtAuthenticationFilter.java`
3. Implement `SecurityConfig.java`
4. Implement `AuthService.java`
5. Implement `AuthController.java`
6. Create a GitHub OAuth App (dev) and configure credentials

**Acceptance criteria:**
- [ ] `GET /api/auth/github` redirects to GitHub OAuth page
- [ ] After authorizing, callback creates user in DB and returns JWT
- [ ] `GET /api/auth/me` with valid JWT returns user info
- [ ] Requests without JWT to protected endpoints return 401

---

### Task 1.4: Repository CRUD

**What to do:**
1. Implement `GitHubClient.java` (getRepository, createWebhook, deleteWebhook)
2. Implement `SqsService.java` (sendIndexingMessage)
3. Implement `RepoService.java` (connectRepo, getUserRepos, disconnectRepo, triggerReindex)
4. Implement `RepoController.java`
5. Implement `ConnectRepoRequest.java` DTO with validation

**Acceptance criteria:**
- [ ] `POST /api/repos` with valid repo name creates repo in DB with status PENDING
- [ ] `POST /api/repos` sends a message to SQS (verify in AWS console or ElasticMQ logs)
- [ ] `GET /api/repos` returns list of user's repos
- [ ] `DELETE /api/repos/{id}` removes repo from DB
- [ ] Connecting a repo you don't have GitHub access to returns 403

---

### Task 1.5: Frontend Scaffold

**What to do:**
1. Create Vite + React + TypeScript project
2. Install all dependencies
3. Set up Tailwind CSS
4. Create all type definitions (`src/types/index.ts`)
5. Create API client with interceptors (`src/api/client.ts`)
6. Create auth store (Zustand)
7. Create LoginPage and OAuthCallbackPage
8. Create basic AppLayout with placeholder content
9. Set up React Router with protected routes

**Acceptance criteria:**
- [ ] `npm run dev` starts frontend on port 5173
- [ ] Login page shows "Sign in with GitHub" button
- [ ] Clicking it redirects to GitHub, and after auth, user lands on the main page
- [ ] JWT is stored in localStorage
- [ ] Refreshing the page keeps user logged in

---

## Sprint 2: Core Features (Days 6-12)

### Goal: Indexing pipeline + RAG query + Chat UI working end-to-end

---

### Task 2.1: Chunking Service (Strategy Pattern)

**What to do:**
1. Create `LanguageChunker.java` interface (Strategy pattern) in `service/chunking/`
2. Implement `JavaChunker.java` — regex-based Java parsing
3. Implement `TypeScriptChunker.java` — TS/JS parsing
4. Implement `PythonChunker.java` — indentation-based parsing
5. Implement `FallbackChunker.java` — block-based fallback
6. Create `ChunkingService.java` interface in `service/`
7. Implement `DefaultChunkingService.java` in `service/impl/` — delegates to the right strategy
8. Implement `detectLanguage()` method

**Reference:** `docs/05-backend-guide-part6.md` (Sections 2-3) and `docs/05-backend-guide-part7.md` (Section 4.1)

**Acceptance criteria:**
- [ ] Unit test: Java file with 3 methods → produces 3 chunks with correct line numbers
- [ ] Unit test: TypeScript file with class + functions → produces correct chunks
- [ ] Unit test: Unknown file type → falls back to FallbackChunker
- [ ] Adding a new language requires only a new @Component class (no existing code changes)
- [ ] Large functions (>1000 tokens) are split into sub-chunks

---

### Task 2.2: Embedding Service (Interface + Impl)

**What to do:**
1. Create `EmbeddingService.java` interface in `service/`
2. Implement `GeminiEmbeddingService.java` in `service/impl/` with circuit breaker
3. Implement `embedText()` for single text
4. Implement `embedBatch()` for batch embedding
5. Implement `toVectorString()` for pgvector format
6. Implement `getDimension()` returning 768

**Reference:** `docs/05-backend-guide-part6.md` (Section 2.2) and `docs/05-backend-guide-part7.md` (Section 4.2)

**Acceptance criteria:**
- [ ] `embedText("hello world")` returns a float array of length 768
- [ ] `embedBatch(["text1", "text2"])` returns 2 embeddings
- [ ] `toVectorString()` produces format like `[0.1,0.2,...]`
- [ ] Circuit breaker opens after 5 consecutive failures
- [ ] QueryService depends on `EmbeddingService` interface, not the Gemini impl directly

---

### Task 2.3: Indexing Pipeline (Full)

**What to do:**
1. Implement `IndexingService.java` — `processFullIndexing()` method
2. Implement repo cloning with JGit
3. Implement file walking with extension filtering
4. Wire up: clone → walk → chunk → embed → store in pgvector
5. Implement progress tracking (update job status in DB)

**Acceptance criteria:**
- [ ] Given a small public repo (e.g., a repo with ~20 files), full indexing completes
- [ ] Chunks are stored in `code_chunks` table with valid embeddings
- [ ] Job status transitions: QUEUED → PROCESSING → COMPLETED
- [ ] Progress percentage updates during processing
- [ ] Temp clone directory is cleaned up after indexing

---

### Task 2.4: SQS Worker

**What to do:**
1. Implement `IndexingWorker.java` — SQS polling with `@Scheduled`
2. Implement message processing (parse JSON, call IndexingService)
3. Implement message deletion on success
4. Implement retry logic (check attempts, move to DLQ after max)
5. Implement `StaleJobCleanup.java`

**Acceptance criteria:**
- [ ] Worker polls SQS every 5 seconds
- [ ] When a message arrives, indexing starts automatically
- [ ] On success, message is deleted from queue
- [ ] On failure, message becomes visible again after visibility timeout
- [ ] Jobs stuck in PROCESSING for >10 min are marked FAILED by cleanup task

---

### Task 2.5: Query Service (RAG Pipeline) + LLM Service + PromptBuilder

**What to do:**
1. Create `LlmService.java` interface in `service/`
2. Implement `GeminiLlmService.java` in `service/impl/` with streaming
3. Implement `PromptBuilder.java` in `service/prompt/` (Builder pattern)
4. Implement `QueryService.java` — full RAG pipeline using interfaces + builder
5. Implement `CacheService.java`
6. Implement `RateLimitService.java`
7. Implement `QueryController.java` with SSE endpoint

**Reference:** `docs/05-backend-guide-part7.md` (Sections 4.3, 5, 6)

**Acceptance criteria:**
- [ ] `POST /api/query` with a question returns streaming SSE response
- [ ] First event is `citations` with relevant code chunks
- [ ] Subsequent events are `token` with streamed text
- [ ] Final event is `done` with metadata
- [ ] Second identical query returns cached result (faster)
- [ ] 21st query in an hour returns 429 rate limit error
- [ ] QueryService depends on `LlmService` and `EmbeddingService` interfaces
- [ ] Prompt is built using `PromptBuilder.create().withCodeChunks(...).withQuestion(...).build()`

---

### Task 2.6: Conversation Service

**What to do:**
1. Implement `ConversationService.java`
2. Implement `ConversationController.java`
3. Wire conversation memory into QueryService (include last 10 messages in prompt)

**Acceptance criteria:**
- [ ] First question creates a new conversation
- [ ] Follow-up questions (with conversationId) add to existing conversation
- [ ] `GET /api/conversations` lists user's conversations
- [ ] `GET /api/conversations/{id}` returns conversation with all messages
- [ ] `DELETE /api/conversations/{id}` removes conversation

---

### Task 2.7: Chat UI (Frontend)

**What to do:**
1. Implement `ChatWindow.tsx`
2. Implement `MessageBubble.tsx` with markdown rendering
3. Implement `CodeCitation.tsx` with expandable code snippets
4. Implement `InputBar.tsx` with Enter-to-send
5. Implement `StreamingMessage.tsx`
6. Implement `useChat.ts` hook with SSE consumption
7. Implement `streamQuestion()` in `query.api.ts`

**Acceptance criteria:**
- [ ] User can type a question and press Enter
- [ ] Response streams in token-by-token (visible typing effect)
- [ ] Code blocks in responses are syntax-highlighted
- [ ] Citations appear with file path and line numbers
- [ ] Clicking a citation expands to show the code snippet
- [ ] Conversation persists — follow-up questions work

---

## Sprint 3: Polish & Deploy (Days 13-21)

### Goal: Complete UI, webhook integration, deployment, and polish

---

### Task 3.1: Repository Management UI

**What to do:**
1. Implement `Sidebar.tsx`
2. Implement `RepoList.tsx` and `RepoCard.tsx`
3. Implement `ConnectRepoModal.tsx`
4. Implement `ConversationList.tsx`
5. Implement `IndexingProgress.tsx` (polling-based)
6. Implement `useRepos.ts` and `useSSE.ts` hooks

**Acceptance criteria:**
- [ ] Sidebar shows list of connected repos with status indicators
- [ ] Clicking "+" opens modal to connect a new repo
- [ ] After connecting, indexing progress shows (percentage bar)
- [ ] Repos with READY status are clickable to start chatting
- [ ] Conversations for the active repo are listed in sidebar
- [ ] Disconnect button removes repo (with confirmation)

---

### Task 3.2: Webhook Integration (Incremental Re-indexing)

**What to do:**
1. Implement `WebhookController.java` — receive GitHub push events
2. Implement webhook signature verification (HMAC SHA-256)
3. Implement `processIncrementalIndexing()` in IndexingService
4. Add webhook creation when connecting a repo (in RepoService)

**Acceptance criteria:**
- [ ] When code is pushed to a connected repo, webhook fires
- [ ] Only changed files are re-indexed (not the entire repo)
- [ ] Old chunks for modified/deleted files are removed
- [ ] Cache is invalidated for the repo after re-indexing
- [ ] Invalid webhook signatures are rejected with 401

---

### Task 3.3: Error Handling & Edge Cases

**What to do:**
1. Implement `GlobalExceptionHandler.java` with all error codes
2. Add proper error states in frontend (error toasts, retry buttons)
3. Handle: repo not indexed yet, rate limit exceeded, LLM unavailable
4. Add loading states for all async operations
5. Handle SSE connection drops (auto-retry or show error)

**Acceptance criteria:**
- [ ] Asking a question on a non-indexed repo shows clear error message
- [ ] Rate limit exceeded shows countdown timer
- [ ] Network errors show toast with retry option
- [ ] Loading spinners appear during all async operations
- [ ] No unhandled promise rejections in browser console

---

### Task 3.4: Deploy Backend to AWS

**What to do:**
1. Create RDS PostgreSQL instance (free tier)
2. Create SQS queues (main + DLQ)
3. Store secrets in SSM Parameter Store
4. Create IAM role + instance profile
5. Launch EC2 instance
6. Build JAR and deploy
7. Run Flyway migrations against RDS
8. Verify all endpoints work

**Acceptance criteria:**
- [ ] Backend is running on EC2 and accessible via public IP:8080
- [ ] Health check endpoint responds: `GET /actuator/health` → 200
- [ ] OAuth flow works end-to-end (GitHub → EC2 → JWT)
- [ ] Indexing works (SQS message → worker processes → chunks in RDS)
- [ ] Query works (question → vector search → streaming response)

---

### Task 3.5: Deploy Frontend to Vercel

**What to do:**
1. Push frontend to GitHub
2. Connect to Vercel
3. Set environment variables (VITE_API_URL)
4. Deploy and verify

**Acceptance criteria:**
- [ ] Frontend is live at `https://your-app.vercel.app`
- [ ] Login with GitHub works
- [ ] Can connect a repo and ask questions
- [ ] SSE streaming works through Vercel's proxy

---

### Task 3.6: Final Polish

**What to do:**
1. Add a proper README.md at the project root (for GitHub)
2. Add screenshots/GIF demo to README
3. Test the full flow end-to-end (login → connect → index → ask → answer)
4. Fix any UI issues (spacing, colors, responsiveness)
5. Add empty states and helpful placeholder text
6. Ensure no console errors or warnings

**Acceptance criteria:**
- [ ] Full flow works without errors
- [ ] README has project description, screenshots, and setup instructions
- [ ] UI looks polished and professional
- [ ] No console errors in production build
- [ ] Lighthouse accessibility score > 80

---

## Summary Timeline

| Week | Sprint | Deliverable |
|------|--------|-------------|
| Week 1 | Sprint 1 | Auth + DB + Repo CRUD + Frontend scaffold |
| Week 2 | Sprint 2 | Indexing pipeline + RAG query + Chat UI |
| Week 3 | Sprint 3 | Webhooks + Polish + Deploy to AWS + Vercel |

---

## Dependencies Between Tasks

```
Task 1.1 (Project Init)
  └── Task 1.2 (Database)
       └── Task 1.3 (Auth)
            └── Task 1.4 (Repo CRUD)
                 └── Task 2.1 (Chunking) ──┐
                 └── Task 2.2 (Embedding) ─┤
                                           └── Task 2.3 (Indexing Pipeline)
                                                └── Task 2.4 (SQS Worker)
                                                └── Task 2.5 (Query/RAG)
                                                     └── Task 2.6 (Conversations)

Task 1.5 (Frontend Scaffold)
  └── Task 2.7 (Chat UI)
       └── Task 3.1 (Repo Management UI)

Task 2.4 + Task 2.5 → Task 3.2 (Webhooks)
Task 3.1 + Task 3.2 → Task 3.3 (Error Handling)
Task 3.3 → Task 3.4 (Deploy Backend)
Task 3.4 → Task 3.5 (Deploy Frontend)
Task 3.5 → Task 3.6 (Polish)
```

---

## Tips for the Implementing Model/Developer

1. **Test each task in isolation** before moving to the next. Don't build the whole thing and debug at the end.
2. **Use Postman/curl** to test backend endpoints before building the frontend for them.
3. **Start with a small repo** (< 20 files) for testing indexing. Don't test with a 10,000-file monorepo.
4. **The chunking service is the hardest part.** Get it working for Java first, then add other languages.
5. **If SQS setup is blocking you**, temporarily replace it with a direct method call (skip the queue) and add SQS later.
6. **The SSE streaming on the frontend** is tricky with POST requests. The `fetch` + `ReadableStream` approach in `query.api.ts` is the correct pattern.
7. **Don't optimize early.** Get it working first, then add caching, rate limiting, and circuit breakers.
