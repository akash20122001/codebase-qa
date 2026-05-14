# Codebase Q&A - Backend

Spring Boot backend for the Codebase Q&A platform.

## Prerequisites

- Java 21 or higher
- Maven 3.8+
- Docker & Docker Compose (for local development)

## Quick Start

### 1. Start Infrastructure

```bash
# Start PostgreSQL + Redis + ElasticMQ (local SQS)
docker-compose up -d

# Verify services are running
docker-compose ps
```

### 2. Configure Environment

```bash
# Copy example env file
cp .env.example .env

# Edit .env with your credentials
# At minimum, you need:
# - GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET (create OAuth app at https://github.com/settings/developers)
# - GEMINI_API_KEY (get from https://aistudio.google.com/app/apikey)
# - JWT_SECRET (generate with: openssl rand -base64 32)
```

### 3. Run the Application

```bash
# Compile
mvn clean compile

# Run with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Or build and run JAR
mvn clean package
java -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

The backend will start on `http://localhost:8080`

## Project Structure

```
src/main/java/com/codebaseqa/
├── CodebaseQaApplication.java       # Main entry point
├── config/                          # Configuration classes
├── controller/                      # REST controllers
├── service/                         # Business logic
│   ├── impl/                        # Service implementations
│   ├── chunking/                    # Code chunking strategies
│   └── prompt/                      # Prompt builder
├── model/                           # JPA entities
├── repository/                      # Spring Data repositories
├── dto/                             # Data transfer objects
│   ├── request/                     # Request DTOs
│   └── response/                    # Response DTOs
├── exception/                       # Custom exceptions
├── util/                            # Utility classes
├── worker/                          # Background workers
└── middleware/                      # Filters and interceptors
```

## Development

### Running Tests

```bash
mvn test
```

### Database Migrations

Flyway migrations are in `src/main/resources/db/migration/`

```bash
# Run migrations
mvn flyway:migrate

# Check migration status
mvn flyway:info

# Clean database (dev only!)
mvn flyway:clean
```

### Accessing Local Services

- **Backend API**: http://localhost:8080
- **PostgreSQL**: localhost:5432 (user: postgres, password: postgres)
- **Redis**: localhost:6379
- **ElasticMQ (SQS)**: http://localhost:9324
- **Health Check**: http://localhost:8080/actuator/health

## API Documentation

See `docs/04-api-specification.md` for complete API documentation.

## Build Plan

Follow the tasks in `docs/09-build-plan.md` for step-by-step implementation.

## Troubleshooting

### Port already in use

```bash
# Check what's using port 8080
lsof -i :8080

# Or change the port in application.yml
server.port=8081
```

### Database connection failed

```bash
# Ensure PostgreSQL is running
docker-compose ps postgres

# Check logs
docker-compose logs postgres
```

### Redis connection failed

```bash
# Ensure Redis is running
docker-compose ps redis

# Test connection
redis-cli ping
```
