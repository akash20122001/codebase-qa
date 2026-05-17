# Codebase Q&A

> A shared, always-up-to-date codebase knowledge base for engineering teams. No local clone required — anyone on the team can ask questions about any connected repository from a browser and get accurate, grounded answers with file/line references.

---

## Table of Contents

- [Project Overview](#project-overview)
- [Key Features](#key-features)
- [Tech Stack](#tech-stack)
- [Architecture Summary](#architecture-summary)
- [Project Structure](#project-structure)
- [Quick Start](#quick-start)

---

## Project Overview

**Codebase Q&A** solves the problem of codebase knowledge being locked behind local development environments. IDE-based AI tools (Cursor, Copilot, Kiro) require each developer to clone the repo, install dependencies, and set up their environment before they can ask a single question. This creates friction for:

- **New hires** — day-1 onboarding without cloning 10GB monorepos
- **Cross-team collaboration** — frontend devs querying backend code they don't have locally
- **Non-technical stakeholders** — PMs and designers understanding where logic lives
- **Incident response** — on-call engineers getting answers without full dev setup
- **Cross-repo understanding** — "How does auth work across our microservices?"

The platform provides a **centralized, always-indexed knowledge base** that stays current via GitHub webhooks. Any team member can ask questions like:

- "How does the authentication middleware work?"
- "Where is the payment processing logic across all services?"
- "What design patterns are used in this project?"
- "Explain the database schema and relationships"

The system uses **AST-aware chunking** (splitting code at function/class boundaries using tree-sitter) combined with **vector similarity search** (pgvector) and **LLM synthesis** (Google Gemini) to provide accurate, grounded answers with exact file and line number references.

### What Makes This Different from IDE-Based AI Tools

| Capability | IDE Tools (Cursor/Kiro) | Codebase Q&A |
|-----------|------------------------|--------------|
| Requires local clone | ✅ Yes | ❌ No |
| Requires dev environment | ✅ Yes | ❌ No |
| Cross-repo queries | ❌ One repo at a time | ✅ All connected repos |
| Shared across team | ❌ Per-developer | ✅ One instance for everyone |
| Non-developers can use | ❌ Need IDE | ✅ Browser-based |
| Always up-to-date | ❌ Must git pull | ✅ Webhook auto-reindex |
| Team conversation history | ❌ Lost on close | ✅ Searchable history |

### Technical Differentiators

1. **AST-aware chunking** — code is split at logical boundaries (functions, classes), not arbitrary token counts
2. **Webhook-driven incremental indexing** — only changed files are re-indexed on push, keeping the knowledge base current without manual intervention
3. **Production backend patterns** — message queues (SQS), caching (Redis), rate limiting, circuit breakers
4. **Multi-tenancy** — team members share indexed repos while maintaining isolated conversations
5. **Conversation memory** — follow-up questions understand context from previous messages

---

## Key Features

### Core Features
- **GitHub OAuth** — connect repositories securely
- **Team-wide access** — shared indexed repos, no local clone needed
- **Intelligent Indexing** — AST-aware code parsing and chunking via tree-sitter
- **Semantic Search** — vector similarity search using pgvector
- **AI-Powered Answers** — Google Gemini generates answers grounded in retrieved code
- **Streaming Responses** — SSE (Server-Sent Events) for token-by-token streaming
- **Conversation Memory** — multi-turn conversations with context retention
- **Code Citations** — answers include clickable file/line references
- **Cross-repo queries** — ask questions spanning multiple connected repositories

### Backend Engineering Features
- **Async Job Processing** — SQS-based queue for repo indexing with DLQ (Dead Letter Queue)
- **Incremental Re-indexing** — GitHub webhooks trigger partial re-index on push (team always has latest code)
- **Caching** — Redis (Upstash) caches frequent queries (team members asking similar questions skip LLM)
- **Rate Limiting** — Token bucket algorithm per user to prevent LLM abuse
- **Circuit Breaker** — Graceful degradation when Gemini API is unavailable (Resilience4j)
- **Multi-tenancy** — Team members share repos while maintaining isolated conversations
- **Scheduled Tasks** — Spring Scheduler for periodic health checks and stale job cleanup

### Frontend Features
- **Chat Interface** — Clean chat UI with markdown + code block rendering
- **Code Citations** — Clickable references that show the source code
- **Repo Manager** — Connect/disconnect repos, view indexing status
- **Indexing Progress** — Real-time progress updates via SSE
- **Conversation History** — Sidebar with past conversations
- **Responsive Design** — Works on desktop and tablet

---

## Tech Stack

### Backend (Spring Boot + Java)
| Technology | Purpose |
|-----------|---------|
| **Java 17** | Language |
| **Spring Boot 3.2** | Application framework |
| **Spring Web** | REST API + SSE support |
| **Spring Data JPA** | ORM + repository pattern |
| **Spring Security + OAuth2 Client** | GitHub OAuth authentication |
| **Spring Scheduling** | Cron jobs for cleanup tasks |
| **PostgreSQL** | Primary database |
| **pgvector (via Hibernate custom types)** | Vector similarity search |
| **AWS SQS (via AWS SDK v2)** | Message queue for async indexing jobs |
| **Upstash Redis (via Spring Data Redis)** | Query caching + rate limiting |
| **Google Gemini API (via REST/WebClient)** | LLM (chat) + embeddings generation |
| **tree-sitter (via JNI/process execution)** | AST parsing for intelligent code chunking |
| **Resilience4j** | Circuit breaker + retry patterns |
| **JGit** | Git operations (clone, diff) in Java |
| **MapStruct** | DTO mapping |
| **Flyway** | Database migrations |

### Frontend (React)
| Technology | Purpose |
|-----------|---------|
| **React 18** | UI framework |
| **TypeScript** | Type safety |
| **Vite** | Build tool |
| **TanStack Query** | Server state management + caching |
| **Zustand** | Client state management |
| **Tailwind CSS** | Styling |
| **React Markdown** | Rendering AI responses with code blocks |
| **React Syntax Highlighter** | Code block highlighting in citations |

### Infrastructure
| Technology | Purpose |
|-----------|---------|
| **AWS EC2 (t3.micro)** | Backend hosting |
| **AWS RDS (PostgreSQL 15)** | Managed database with pgvector |
| **AWS SQS** | Managed message queue + DLQ |
| **AWS SSM Parameter Store** | Secrets management |
| **Upstash Redis** | Managed Redis (free tier) |
| **Vercel** | Frontend hosting |
| **GitHub Webhooks** | Trigger incremental re-indexing |

---

## Architecture Summary

```
┌─────────────────────────────────┐
│     Vercel (Frontend - React)   │
│                                 │
│  Chat UI │ Repo Manager │ Auth  │
└──────────────┬──────────────────┘
               │ HTTPS + SSE
               │
┌──────────────▼──────────────────────────────┐
│   EC2 (Backend - Spring Boot)               │
│                                             │
│  ┌──────────────┐  ┌────────────────────┐  │
│  │ Controllers  │  │ SSE Emitters       │  │
│  │ (REST API)   │  │ (Streaming)        │  │
│  └──────┬───────┘  └────────┬───────────┘  │
│         │                    │              │
│  ┌──────▼────────────────────▼───────────┐  │
│  │         Service Layer                 │  │
│  │  • AuthService                        │  │
│  │  • RepoService                        │  │
│  │  • IndexingService                    │  │
│  │  • QueryService                       │  │
│  │  • ConversationService                │  │
│  │  • EmbeddingService                   │  │
│  │  • ChunkingService                    │  │
│  │  • CacheService                       │  │
│  └──────┬────────────────────────────────┘  │
│         │                                   │
│  ┌──────▼──────┐  ┌──────────────────────┐  │
│  │ SQS Listener│  │ Rate Limiter         │  │
│  │ (@SqsListener)│ │ (Redis + Bucket4j)  │  │
│  └─────────────┘  └──────────────────────┘  │
└──┬──────────┬────────────────────────────────┘
   │          │
┌──▼──┐  ┌───▼────┐  ┌──────────┐
│ RDS │  │Upstash │  │ Google   │
│(PG +│  │(Redis) │  │ Gemini   │
│pgvec)│ │        │  │ API      │
└─────┘  └────────┘  └──────────┘
```

---

## Project Structure

```
codebase-qa/
├── docs/                              # This documentation
│   ├── 01-README.md                   # Project overview (this file)
│   ├── 02-architecture.md            # Detailed system architecture
│   ├── 03-database-schema.md         # Database design + migrations
│   ├── 04-api-specification.md       # All API endpoints
│   ├── 05-backend-guide.md           # Backend implementation guide
│   ├── 06-frontend-guide.md          # Frontend implementation guide
│   ├── 07-infrastructure.md          # AWS + Vercel deployment
│   ├── 08-configuration.md           # Environment variables
│   └── 09-build-plan.md              # Sprint breakdown
│
├── backend/
│   ├── src/main/java/com/codebaseqa/
│   │   ├── CodebaseQaApplication.java        # Main Spring Boot entry point
│   │   ├── config/
│   │   │   ├── SecurityConfig.java           # Spring Security + OAuth2
│   │   │   ├── RedisConfig.java              # Redis connection + cache config
│   │   │   ├── SqsConfig.java               # AWS SQS client config
│   │   │   ├── WebConfig.java                # CORS, SSE timeout config
│   │   │   └── ResilienceConfig.java         # Circuit breaker config
│   │   ├── controller/
│   │   │   ├── AuthController.java           # OAuth callback, user info
│   │   │   ├── RepoController.java           # Connect/disconnect repos
│   │   │   ├── QueryController.java          # Ask questions (SSE streaming)
│   │   │   └── ConversationController.java   # Conversation CRUD
│   │   ├── service/
│   │   │   ├── AuthService.java              # Concrete (no interface needed)
│   │   │   ├── RepoService.java              # Concrete
│   │   │   ├── QueryService.java             # Concrete (orchestrator)
│   │   │   ├── ConversationService.java      # Concrete
│   │   │   ├── CacheService.java             # Concrete
│   │   │   ├── RateLimitService.java         # Concrete
│   │   │   ├── SqsService.java              # Concrete
│   │   │   ├── ChunkingService.java          # INTERFACE
│   │   │   ├── EmbeddingService.java         # INTERFACE
│   │   │   ├── LlmService.java              # INTERFACE
│   │   │   └── impl/
│   │   │       ├── DefaultChunkingService.java   # Strategy-based impl
│   │   │       ├── GeminiEmbeddingService.java   # Gemini embedding impl
│   │   │       └── GeminiLlmService.java         # Gemini chat impl
│   │   ├── service/chunking/                 # STRATEGY PATTERN
│   │   │   ├── LanguageChunker.java          # Strategy interface
│   │   │   ├── JavaChunker.java              # Java-specific parsing
│   │   │   ├── TypeScriptChunker.java        # TS/JS-specific parsing
│   │   │   ├── PythonChunker.java            # Python-specific parsing
│   │   │   └── FallbackChunker.java          # Block-based fallback
│   │   ├── service/prompt/                   # BUILDER PATTERN
│   │   │   └── PromptBuilder.java            # Assembles LLM prompts
│   │   ├── worker/
│   │   │   ├── IndexingWorker.java           # SQS message consumer
│   │   │   └── StaleJobCleanup.java          # Scheduled cleanup of stuck jobs
│   │   ├── repository/                       # Spring Data JPA repositories
│   │   │   ├── UserRepository.java
│   │   │   ├── RepoRepository.java
│   │   │   ├── ChunkRepository.java
│   │   │   ├── ConversationRepository.java
│   │   │   ├── MessageRepository.java
│   │   │   └── IndexingJobRepository.java
│   │   ├── model/                            # JPA entities
│   │   │   ├── User.java
│   │   │   ├── Repo.java
│   │   │   ├── CodeChunk.java
│   │   │   ├── Conversation.java
│   │   │   ├── Message.java
│   │   │   └── IndexingJob.java
│   │   ├── dto/                              # Request/Response DTOs
│   │   │   ├── request/
│   │   │   │   ├── ConnectRepoRequest.java
│   │   │   │   ├── AskQuestionRequest.java
│   │   │   │   └── CreateConversationRequest.java
│   │   │   └── response/
│   │   │       ├── RepoResponse.java
│   │   │       ├── ConversationResponse.java
│   │   │       ├── MessageResponse.java
│   │   │       └── IndexingStatusResponse.java
│   │   ├── exception/
│   │   │   ├── GlobalExceptionHandler.java   # @ControllerAdvice
│   │   │   ├── RateLimitExceededException.java
│   │   │   ├── RepoNotFoundException.java
│   │   │   └── IndexingFailedException.java
│   │   └── util/
│   │       ├── CircuitBreakerUtil.java
│   │       ├── GitHubClient.java             # GitHub API wrapper
│   │       └── TreeSitterParser.java         # tree-sitter JNI wrapper
│   ├── src/main/resources/
│   │   ├── application.yml                   # Main config
│   │   ├── application-dev.yml               # Dev profile
│   │   ├── application-prod.yml              # Prod profile
│   │   └── db/migration/                     # Flyway migrations
│   │       ├── V1__initial_schema.sql
│   │       ├── V2__pgvector_setup.sql
│   │       └── V3__indexing_jobs.sql
│   ├── pom.xml                               # Maven dependencies
│   └── Dockerfile
│
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── Chat/
│   │   │   │   ├── ChatWindow.tsx
│   │   │   │   ├── MessageBubble.tsx
│   │   │   │   ├── CodeCitation.tsx
│   │   │   │   ├── InputBar.tsx
│   │   │   │   └── StreamingMessage.tsx
│   │   │   ├── Repo/
│   │   │   │   ├── RepoList.tsx
│   │   │   │   ├── RepoCard.tsx
│   │   │   │   ├── ConnectRepoModal.tsx
│   │   │   │   └── IndexingProgress.tsx
│   │   │   ├── Sidebar/
│   │   │   │   ├── Sidebar.tsx
│   │   │   │   ├── ConversationList.tsx
│   │   │   │   └── RepoSelector.tsx
│   │   │   ├── Layout/
│   │   │   │   ├── AppLayout.tsx
│   │   │   │   ├── Header.tsx
│   │   │   │   └── AuthGuard.tsx
│   │   │   └── common/
│   │   │       ├── Button.tsx
│   │   │       ├── Modal.tsx
│   │   │       ├── Spinner.tsx
│   │   │       └── Toast.tsx
│   │   ├── hooks/
│   │   │   ├── useAuth.ts
│   │   │   ├── useChat.ts
│   │   │   ├── useSSE.ts
│   │   │   └── useRepos.ts
│   │   ├── stores/
│   │   │   ├── authStore.ts
│   │   │   └── chatStore.ts
│   │   ├── api/
│   │   │   ├── client.ts                     # Axios instance with interceptors
│   │   │   ├── auth.api.ts
│   │   │   ├── repo.api.ts
│   │   │   ├── query.api.ts
│   │   │   └── conversation.api.ts
│   │   ├── types/
│   │   │   └── index.ts
│   │   ├── utils/
│   │   │   └── markdown.ts
│   │   ├── App.tsx
│   │   └── main.tsx
│   ├── package.json
│   ├── vite.config.ts
│   ├── tailwind.config.js
│   └── .env.example
└── README.md                                  # Root readme pointing to docs/
```

---

## Quick Start

### Prerequisites
- Java 17+ (JDK)
- Maven 3.8+
- Node.js 18+ (for frontend)
- AWS Account (free tier)
- GitHub OAuth App
- Google Gemini API key (free)
- Neon PostgreSQL account (free) OR AWS RDS
- Upstash Redis account (free)

### Setup Steps
1. Clone the repo
2. Set up environment variables (see `docs/08-configuration.md`)
3. Run Flyway migrations: `mvn flyway:migrate`
4. Start backend: `mvn spring-boot:run`
5. Start frontend: `cd frontend && npm run dev`
6. Connect a GitHub repo and start asking questions

Detailed setup instructions are in `docs/07-infrastructure.md`.
