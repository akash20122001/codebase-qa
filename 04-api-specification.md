# API Specification

---

## Base URL

- **Development:** `http://localhost:8080/api`
- **Production:** `https://your-ec2-domain.com/api`

## Authentication

All endpoints (except auth endpoints) require a JWT token in the `Authorization` header:

```
Authorization: Bearer <jwt_token>
```

## Common Response Format

### Success Response
```json
{
  "data": { ... },
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### Error Response
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "You have exceeded the rate limit. Please wait 30 seconds.",
    "retryAfter": 30
  },
  "timestamp": "2024-01-15T10:30:00Z"
}
```

---

## 1. Authentication Endpoints

### 1.1 GET `/api/auth/github`

Redirects user to GitHub OAuth authorization page.

**Response:** `302 Redirect` to `https://github.com/login/oauth/authorize?client_id=...&scope=repo,read:user`

---

### 1.2 GET `/api/auth/github/callback`

GitHub redirects here after user authorizes. Exchanges code for token, creates/updates user, returns JWT.

**Query Parameters:**
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `code` | string | Yes | OAuth authorization code from GitHub |
| `state` | string | Yes | CSRF protection state parameter |

**Success Response (200):**
```json
{
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "user": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "username": "akashsoni",
      "email": "akash@example.com",
      "avatarUrl": "https://avatars.githubusercontent.com/u/12345"
    }
  }
}
```

**Error Response (401):**
```json
{
  "error": {
    "code": "OAUTH_FAILED",
    "message": "Failed to authenticate with GitHub. Please try again."
  }
}
```

---

### 1.3 GET `/api/auth/me`

Returns the current authenticated user's profile.

**Headers:** `Authorization: Bearer <token>`

**Success Response (200):**
```json
{
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "username": "akashsoni",
    "email": "akash@example.com",
    "avatarUrl": "https://avatars.githubusercontent.com/u/12345",
    "createdAt": "2024-01-10T08:00:00Z"
  }
}
```

---

### 1.4 POST `/api/auth/logout`

Invalidates the current JWT (adds to blacklist in Redis with TTL = token expiry).

**Headers:** `Authorization: Bearer <token>`

**Success Response (200):**
```json
{
  "data": {
    "message": "Logged out successfully"
  }
}
```

---

## 2. Repository Endpoints

### 2.1 POST `/api/repos`

Connect a GitHub repository and trigger indexing.

**Headers:** `Authorization: Bearer <token>`

**Request Body:**
```json
{
  "repoFullName": "octocat/hello-world",
  "branch": "main"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `repoFullName` | string | Yes | GitHub repo in "owner/name" format |
| `branch` | string | No | Branch to index (default: repo's default branch) |

**Success Response (202 Accepted):**
```json
{
  "data": {
    "id": "660e8400-e29b-41d4-a716-446655440001",
    "fullName": "octocat/hello-world",
    "branch": "main",
    "status": "PENDING",
    "jobId": "770e8400-e29b-41d4-a716-446655440002",
    "createdAt": "2024-01-15T10:30:00Z"
  }
}
```

**Error Responses:**
- `400` — Invalid repo name format
- `403` — User doesn't have access to this repo on GitHub
- `409` — Repo already connected

---

### 2.2 GET `/api/repos`

List all connected repositories for the current user.

**Headers:** `Authorization: Bearer <token>`

**Success Response (200):**
```json
{
  "data": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "fullName": "octocat/hello-world",
      "branch": "main",
      "status": "READY",
      "totalChunks": 245,
      "lastIndexedAt": "2024-01-15T10:35:00Z",
      "createdAt": "2024-01-15T10:30:00Z"
    },
    {
      "id": "660e8400-e29b-41d4-a716-446655440003",
      "fullName": "octocat/spoon-knife",
      "branch": "main",
      "status": "INDEXING",
      "totalChunks": 0,
      "lastIndexedAt": null,
      "createdAt": "2024-01-15T11:00:00Z"
    }
  ]
}
```

---

### 2.3 GET `/api/repos/{repoId}`

Get details of a specific connected repository.

**Headers:** `Authorization: Bearer <token>`

**Path Parameters:**
| Param | Type | Description |
|-------|------|-------------|
| `repoId` | UUID | Repository ID |

**Success Response (200):**
```json
{
  "data": {
    "id": "660e8400-e29b-41d4-a716-446655440001",
    "fullName": "octocat/hello-world",
    "branch": "main",
    "status": "READY",
    "totalChunks": 245,
    "lastIndexedAt": "2024-01-15T10:35:00Z",
    "webhookActive": true,
    "createdAt": "2024-01-15T10:30:00Z"
  }
}
```

---

### 2.4 DELETE `/api/repos/{repoId}`

Disconnect a repository. Deletes all chunks, conversations, and removes the webhook.

**Headers:** `Authorization: Bearer <token>`

**Success Response (200):**
```json
{
  "data": {
    "message": "Repository disconnected successfully"
  }
}
```

---

### 2.5 POST `/api/repos/{repoId}/reindex`

Manually trigger a full re-index of the repository.

**Headers:** `Authorization: Bearer <token>`

**Success Response (202 Accepted):**
```json
{
  "data": {
    "jobId": "770e8400-e29b-41d4-a716-446655440005",
    "status": "QUEUED"
  }
}
```

**Error Response (409):**
```json
{
  "error": {
    "code": "INDEXING_IN_PROGRESS",
    "message": "This repository is already being indexed."
  }
}
```

---

### 2.6 GET `/api/repos/{repoId}/indexing-status`

Get the current indexing job status (SSE endpoint for real-time updates).

**Headers:** `Authorization: Bearer <token>`, `Accept: text/event-stream`

**Response:** Server-Sent Events stream

```
event: progress
data: {"jobId":"770e...","status":"PROCESSING","progress":45,"processedFiles":54,"totalFiles":120}

event: progress
data: {"jobId":"770e...","status":"PROCESSING","progress":78,"processedFiles":94,"totalFiles":120}

event: complete
data: {"jobId":"770e...","status":"COMPLETED","totalChunks":245}

```

If indexing fails:
```
event: error
data: {"jobId":"770e...","status":"FAILED","errorMessage":"Failed to clone repository: authentication required"}

```

---

### 2.7 POST `/api/repos/webhook/github`

Receives GitHub push webhooks for incremental re-indexing.

**Headers:** `X-Hub-Signature-256: sha256=<hmac>` (verified by backend)

**Request Body:** GitHub push event payload (see [GitHub docs](https://docs.github.com/en/webhooks/webhook-events-and-payloads#push))

**Success Response (200):**
```json
{
  "data": {
    "message": "Webhook processed",
    "jobId": "770e8400-e29b-41d4-a716-446655440006"
  }
}
```

---

## 3. Query Endpoints

### 3.1 POST `/api/query`

Ask a question about a connected repository. Returns a streaming SSE response.

**Headers:** `Authorization: Bearer <token>`, `Accept: text/event-stream`

**Request Body:**
```json
{
  "repoId": "660e8400-e29b-41d4-a716-446655440001",
  "conversationId": "880e8400-e29b-41d4-a716-446655440010",
  "question": "How does the authentication middleware work?"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `repoId` | UUID | Yes | Which repo to query |
| `conversationId` | UUID | No | Existing conversation (for follow-ups). If null, creates new conversation. |
| `question` | string | Yes | The user's question (max 1000 chars) |

**Response:** Server-Sent Events stream

```
event: citations
data: [{"filePath":"src/auth/middleware.ts","startLine":15,"endLine":42,"chunkName":"authenticateUser","snippet":"..."}]

event: token
data: {"content":"The"}

event: token
data: {"content":" authentication"}

event: token
data: {"content":" middleware"}

event: token
data: {"content":" in"}

event: token
data: {"content":" `src/auth/middleware.ts`"}

... (more tokens)

event: done
data: {"messageId":"990e...","conversationId":"880e...","tokenCount":342}

```

**Event types:**
| Event | Description |
|-------|-------------|
| `citations` | Sent first — the code chunks retrieved by vector search |
| `token` | Individual tokens as they stream from the LLM |
| `done` | Final event — includes message ID and metadata |
| `error` | If something goes wrong mid-stream |

**Error Responses:**
- `400` — Repo not indexed yet (`REPO_NOT_INDEXED`)
- `429` — Rate limit exceeded (`RATE_LIMIT_EXCEEDED`)
- `503` — LLM unavailable, circuit open (`LLM_UNAVAILABLE`)

---

## 4. Conversation Endpoints

### 4.1 GET `/api/conversations`

List all conversations for the current user.

**Headers:** `Authorization: Bearer <token>`

**Query Parameters:**
| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `repoId` | UUID | No | Filter by repo |
| `page` | int | No | Page number (default: 0) |
| `size` | int | No | Page size (default: 20, max: 50) |

**Success Response (200):**
```json
{
  "data": {
    "conversations": [
      {
        "id": "880e8400-e29b-41d4-a716-446655440010",
        "repoId": "660e8400-e29b-41d4-a716-446655440001",
        "repoFullName": "octocat/hello-world",
        "title": "How does authentication work?",
        "messageCount": 6,
        "createdAt": "2024-01-15T10:30:00Z",
        "updatedAt": "2024-01-15T10:45:00Z"
      }
    ],
    "totalCount": 12,
    "page": 0,
    "size": 20
  }
}
```

---

### 4.2 GET `/api/conversations/{conversationId}`

Get a conversation with all its messages.

**Headers:** `Authorization: Bearer <token>`

**Success Response (200):**
```json
{
  "data": {
    "id": "880e8400-e29b-41d4-a716-446655440010",
    "repoId": "660e8400-e29b-41d4-a716-446655440001",
    "repoFullName": "octocat/hello-world",
    "title": "How does authentication work?",
    "messages": [
      {
        "id": "990e8400-e29b-41d4-a716-446655440020",
        "role": "user",
        "content": "How does the authentication middleware work?",
        "citations": null,
        "createdAt": "2024-01-15T10:30:00Z"
      },
      {
        "id": "990e8400-e29b-41d4-a716-446655440021",
        "role": "assistant",
        "content": "The authentication middleware in `src/auth/middleware.ts` uses JWT tokens...",
        "citations": [
          {
            "filePath": "src/auth/middleware.ts",
            "startLine": 15,
            "endLine": 42,
            "chunkName": "authenticateUser",
            "snippet": "export function authenticateUser(req, res, next) {\n  const token = req.headers.authorization?.split(' ')[1];\n  ..."
          }
        ],
        "createdAt": "2024-01-15T10:30:05Z"
      }
    ],
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:45:00Z"
  }
}
```

---

### 4.3 DELETE `/api/conversations/{conversationId}`

Delete a conversation and all its messages.

**Headers:** `Authorization: Bearer <token>`

**Success Response (200):**
```json
{
  "data": {
    "message": "Conversation deleted successfully"
  }
}
```

---

## 5. Rate Limiting

Rate limiting is applied per user using a **token bucket algorithm**:

| Resource | Limit | Window |
|----------|-------|--------|
| Query (ask question) | 20 requests | per hour |
| Repo connect | 5 requests | per hour |
| General API | 100 requests | per minute |

**Rate limit headers (included in all responses):**
```
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 15
X-RateLimit-Reset: 1705312200
```

**When exceeded (429):**
```json
{
  "error": {
    "code": "RATE_LIMIT_EXCEEDED",
    "message": "Query rate limit exceeded. You can make 20 queries per hour.",
    "retryAfter": 1823
  }
}
```

---

## 6. SSE (Server-Sent Events) Implementation Notes

### For the Backend Developer

Spring Boot SSE implementation using `SseEmitter`:

```java
@PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter askQuestion(@RequestBody AskQuestionRequest request,
                               @AuthenticationPrincipal UserPrincipal user) {
    SseEmitter emitter = new SseEmitter(120_000L); // 2 min timeout

    // Process async to not block the request thread
    CompletableFuture.runAsync(() -> {
        try {
            queryService.streamAnswer(request, user, emitter);
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

### For the Frontend Developer

Consuming SSE in React:

```typescript
const eventSource = new EventSource('/api/query', {
  // Note: EventSource doesn't support POST natively.
  // Use fetch with ReadableStream instead (see frontend guide).
});
```

Since `EventSource` doesn't support POST or custom headers, the frontend should use `fetch` with a `ReadableStream` reader. See `docs/06-frontend-guide.md` for the full implementation.

---

## 7. Webhook Payload (GitHub Push Event)

The backend receives this when code is pushed to a connected repo:

```json
{
  "ref": "refs/heads/main",
  "repository": {
    "id": 123456,
    "full_name": "octocat/hello-world"
  },
  "commits": [
    {
      "added": ["src/new-file.ts"],
      "removed": ["src/old-file.ts"],
      "modified": ["src/auth/middleware.ts"]
    }
  ]
}
```

The backend extracts all added + modified files, deletes old chunks for removed + modified files, and re-indexes only those files.
