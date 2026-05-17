# Configuration & Environment Variables

---

## 1. Backend Environment Variables

### All Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_HOST` | Yes | `localhost` | PostgreSQL host |
| `DB_PORT` | No | `5432` | PostgreSQL port |
| `DB_NAME` | No | `codebaseqa` | Database name |
| `DB_USERNAME` | Yes | `postgres` | Database username |
| `DB_PASSWORD` | Yes | — | Database password |
| `REDIS_URL` | Yes | `redis://localhost:6379` | Upstash Redis URL (with auth) |
| `JWT_SECRET` | Yes | — | Min 32 chars, used for signing JWTs |
| `GITHUB_CLIENT_ID` | Yes | — | GitHub OAuth App client ID |
| `GITHUB_CLIENT_SECRET` | Yes | — | GitHub OAuth App client secret |
| `GITHUB_REDIRECT_URI` | Yes | `http://localhost:8080/api/auth/github/callback` | OAuth callback URL |
| `GEMINI_API_KEY` | Yes | — | Google Gemini API key |
| `AWS_REGION` | Yes | `us-east-1` | AWS region for SQS |
| `SQS_QUEUE_URL` | Yes | — | SQS indexing queue URL |
| `SQS_DLQ_URL` | No | — | SQS dead letter queue URL |
| `FRONTEND_URL` | Yes | `http://localhost:5173` | Frontend URL for CORS |
| `INDEXING_TEMP_DIR` | No | `/tmp/codebase-qa` | Temp directory for cloning repos |

### .env.example (Backend)

```env
# Database (PostgreSQL + pgvector)
DB_HOST=localhost
DB_PORT=5432
DB_NAME=codebaseqa
DB_USERNAME=postgres
DB_PASSWORD=your_password_here

# Redis (Upstash)
REDIS_URL=redis://default:your_password@your-endpoint.upstash.io:6379

# JWT
JWT_SECRET=your-super-secret-key-minimum-32-characters-long

# GitHub OAuth
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret
GITHUB_REDIRECT_URI=http://localhost:8080/api/auth/github/callback

# Google Gemini
GEMINI_API_KEY=your_gemini_api_key

# AWS
AWS_REGION=us-east-1
SQS_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789/codebase-qa-indexing
SQS_DLQ_URL=https://sqs.us-east-1.amazonaws.com/123456789/codebase-qa-indexing-dlq

# App
FRONTEND_URL=http://localhost:5173
INDEXING_TEMP_DIR=/tmp/codebase-qa
```

---

## 2. Frontend Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `VITE_API_URL` | Yes | — | Backend API base URL |

### .env.example (Frontend)

```env
# Backend API URL
VITE_API_URL=http://localhost:8080
```

### Production (Vercel)

```env
VITE_API_URL=http://your-ec2-ip:8080
```

---

## 3. Spring Boot Profiles

### application.yml (shared/default)

Contains all config with `${ENV_VAR:default}` syntax. Used for local development.

### application-dev.yml

```yaml
# Dev-specific overrides
spring:
  jpa:
    show-sql: true
  flyway:
    clean-on-validation-error: true

logging:
  level:
    com.codebaseqa: DEBUG
    org.springframework.web: DEBUG
```

### application-prod.yml

```yaml
# Production overrides
spring:
  jpa:
    show-sql: false

logging:
  level:
    com.codebaseqa: INFO
    org.springframework.web: WARN

server:
  # Increase timeout for SSE connections
  tomcat:
    connection-timeout: 120000
```

---

## 4. How to Get Each API Key/Secret

### 4.1 GitHub OAuth App

1. Go to: https://github.com/settings/developers
2. Click "New OAuth App"
3. Fill in:
   - **Application name:** `Codebase QA (Dev)`
   - **Homepage URL:** `http://localhost:5173`
   - **Authorization callback URL:** `http://localhost:8080/api/auth/github/callback`
4. Click "Register application"
5. Copy **Client ID** → `GITHUB_CLIENT_ID`
6. Generate a client secret → `GITHUB_CLIENT_SECRET`

### 4.2 Google Gemini API Key

1. Go to: https://aistudio.google.com/app/apikey
2. Click "Create API Key"
3. Select or create a Google Cloud project
4. Copy the key → `GEMINI_API_KEY`

**Free tier limits:**
- 15 requests per minute
- 1 million tokens per day
- 1,500 requests per day

### 4.3 Upstash Redis

1. Go to: https://console.upstash.com/
2. Create a new Redis database
3. Select the free tier
4. Copy the Redis URL (with password) → `REDIS_URL`

**Free tier limits:**
- 10,000 commands per day
- 256MB storage

### 4.4 Neon PostgreSQL (Alternative to RDS)

1. Go to: https://console.neon.tech/
2. Create a new project
3. Copy the connection string → use to construct `DB_HOST`, `DB_PORT`, etc.
4. Enable pgvector: `CREATE EXTENSION IF NOT EXISTS vector;`

**Free tier limits:**
- 512MB storage
- 1 project

### 4.5 AWS SQS

1. Go to AWS Console → SQS
2. Create queue "codebase-qa-indexing-dlq" (Standard queue)
3. Create queue "codebase-qa-indexing" (Standard queue) with:
   - Dead-letter queue: point to the DLQ
   - Max receives: 3
   - Visibility timeout: 600 seconds
4. Copy the queue URL → `SQS_QUEUE_URL`

---

## 5. Local Development Setup

### 5.1 Using Docker for PostgreSQL + Redis (Local)

If you want to run everything locally without cloud services:

```yaml
# docker-compose.yml (for local dev only)
version: '3.8'
services:
  postgres:
    image: pgvector/pgvector:pg15
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: codebaseqa
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    volumes:
      - pgdata:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

volumes:
  pgdata:
```

```bash
docker-compose up -d
```

### 5.2 Local SQS Alternative (ElasticMQ)

For local development without AWS, use ElasticMQ (SQS-compatible):

```yaml
# Add to docker-compose.yml
  elasticmq:
    image: softwaremill/elasticmq-native
    ports:
      - "9324:9324"
```

Then set `SQS_QUEUE_URL=http://localhost:9324/queue/codebase-qa-indexing`

### 5.3 Running Locally

```bash
# Terminal 1: Start infrastructure
docker-compose up -d

# Terminal 2: Start backend
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3: Start frontend
cd frontend
npm run dev
```

---

## 6. Important Configuration Notes

1. **CORS:** The `FRONTEND_URL` must exactly match the origin of your frontend (including protocol and port). For Vercel, it's `https://your-app.vercel.app`.

2. **JWT Secret:** Must be at least 32 characters (256 bits) for HS256 signing. Generate one with: `openssl rand -base64 32`

3. **GitHub OAuth Redirect:** Must exactly match what's configured in the GitHub OAuth App settings. For production, update it to your EC2 domain.

4. **Upstash Redis URL:** Includes authentication. Format: `redis://default:PASSWORD@ENDPOINT:PORT`

5. **SQS Permissions:** The EC2 instance needs IAM permissions to send/receive/delete SQS messages. This is handled by the instance profile (see infrastructure guide).

6. **pgvector:** Must be enabled on the database before running Flyway migrations. Run `CREATE EXTENSION IF NOT EXISTS vector;` manually or in a pre-migration script.
