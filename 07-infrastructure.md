# Infrastructure & Deployment Guide

---

## 1. Architecture Diagram (Deployed)

```
┌──────────────────┐         ┌─────────────────────────────────────┐
│   User Browser   │         │              AWS Cloud               │
│                  │         │                                     │
│  React App       │ HTTPS   │  ┌───────────────────────────────┐  │
│  (Vercel CDN)   ─┼────────┼──►  EC2 (t3.micro)               │  │
│                  │         │  │  Spring Boot App               │  │
└──────────────────┘         │  │  - API Server                  │  │
                             │  │  - SQS Worker (same process)   │  │
                             │  └──────┬──────────┬──────────────┘  │
                             │         │          │                 │
                             │  ┌──────▼───┐  ┌───▼────────────┐   │
                             │  │   RDS    │  │     SQS        │   │
                             │  │PostgreSQL│  │ + Dead Letter Q │   │
                             │  │+ pgvector│  └────────────────┘   │
                             │  └──────────┘                       │
                             │                                     │
                             │  ┌──────────────────────────────┐   │
                             │  │  SSM Parameter Store          │   │
                             │  │  (secrets)                    │   │
                             │  └──────────────────────────────┘   │
                             └─────────────────────────────────────┘
                                            │
                             ┌──────────────▼──────────────┐
                             │  External Services           │
                             │  • Upstash Redis (free tier) │
                             │  • Google Gemini API (free)  │
                             │  • GitHub API                │
                             └─────────────────────────────┘
```

---

## 2. AWS Setup (Free Tier)

### 2.1 Prerequisites

- AWS Account (< 12 months old for free tier)
- AWS CLI installed and configured (`aws configure`)
- A key pair for EC2 SSH access

### 2.2 Create VPC & Security Group

```bash
# Create security group (use default VPC)
aws ec2 create-security-group \
  --group-name codebase-qa-sg \
  --description "Security group for Codebase QA"

# Allow SSH (your IP only)
aws ec2 authorize-security-group-ingress \
  --group-name codebase-qa-sg \
  --protocol tcp --port 22 \
  --cidr YOUR_IP/32

# Allow HTTP from anywhere (API)
aws ec2 authorize-security-group-ingress \
  --group-name codebase-qa-sg \
  --protocol tcp --port 8080 \
  --cidr 0.0.0.0/0

# Allow HTTPS
aws ec2 authorize-security-group-ingress \
  --group-name codebase-qa-sg \
  --protocol tcp --port 443 \
  --cidr 0.0.0.0/0
```

### 2.3 Create RDS PostgreSQL Instance

```bash
aws rds create-db-instance \
  --db-instance-identifier codebase-qa-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --engine-version 15.4 \
  --master-username postgres \
  --master-user-password YOUR_SECURE_PASSWORD \
  --allocated-storage 20 \
  --db-name codebaseqa \
  --publicly-accessible \
  --vpc-security-group-ids sg-xxxxx \
  --backup-retention-period 0 \
  --no-multi-az
```

**After creation, enable pgvector:**
```sql
-- Connect to the database and run:
CREATE EXTENSION IF NOT EXISTS vector;
```

### 2.4 Create SQS Queues

```bash
# Dead Letter Queue (DLQ)
aws sqs create-queue \
  --queue-name codebase-qa-indexing-dlq

# Main queue with DLQ configured
aws sqs create-queue \
  --queue-name codebase-qa-indexing \
  --attributes '{
    "RedrivePolicy": "{\"deadLetterTargetArn\":\"arn:aws:sqs:us-east-1:ACCOUNT_ID:codebase-qa-indexing-dlq\",\"maxReceiveCount\":\"3\"}",
    "VisibilityTimeout": "600",
    "MessageRetentionPeriod": "86400"
  }'
```

**SQS Configuration explained:**
- `VisibilityTimeout: 600` — 10 minutes for indexing to complete before message becomes visible again
- `maxReceiveCount: 3` — After 3 failed attempts, move to DLQ
- `MessageRetentionPeriod: 86400` — Keep messages for 24 hours

### 2.5 Store Secrets in SSM Parameter Store

```bash
aws ssm put-parameter --name "/codebase-qa/db-password" \
  --value "YOUR_DB_PASSWORD" --type SecureString

aws ssm put-parameter --name "/codebase-qa/jwt-secret" \
  --value "your-256-bit-secret-key-minimum-32-characters" --type SecureString

aws ssm put-parameter --name "/codebase-qa/github-client-id" \
  --value "your_github_oauth_client_id" --type SecureString

aws ssm put-parameter --name "/codebase-qa/github-client-secret" \
  --value "your_github_oauth_client_secret" --type SecureString

aws ssm put-parameter --name "/codebase-qa/gemini-api-key" \
  --value "your_gemini_api_key" --type SecureString

aws ssm put-parameter --name "/codebase-qa/redis-url" \
  --value "redis://default:password@your-instance.upstash.io:6379" --type SecureString

aws ssm put-parameter --name "/codebase-qa/webhook-secret" \
  --value "your_webhook_secret" --type SecureString
```

### 2.6 Create IAM Role for EC2

```bash
# Create role
aws iam create-role \
  --role-name codebase-qa-ec2-role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ec2.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach SQS policy
aws iam put-role-policy \
  --role-name codebase-qa-ec2-role \
  --policy-name sqs-access \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"],
      "Resource": "arn:aws:sqs:us-east-1:ACCOUNT_ID:codebase-qa-*"
    }]
  }'

# Attach SSM read policy
aws iam put-role-policy \
  --role-name codebase-qa-ec2-role \
  --policy-name ssm-read \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": ["ssm:GetParameter", "ssm:GetParameters"],
      "Resource": "arn:aws:ssm:us-east-1:ACCOUNT_ID:parameter/codebase-qa/*"
    }]
  }'

# Create instance profile
aws iam create-instance-profile --instance-profile-name codebase-qa-profile
aws iam add-role-to-instance-profile \
  --instance-profile-name codebase-qa-profile \
  --role-name codebase-qa-ec2-role
```

### 2.7 Launch EC2 Instance

```bash
aws ec2 run-instances \
  --image-id ami-0c7217cdde317cfec \  # Amazon Linux 2023 (us-east-1)
  --instance-type t3.micro \
  --key-name your-key-pair \
  --security-group-ids sg-xxxxx \
  --iam-instance-profile Name=codebase-qa-profile \
  --user-data file://ec2-setup.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=codebase-qa}]'
```

### 2.8 EC2 Setup Script (ec2-setup.sh)

```bash
#!/bin/bash
# Install Java 17
sudo yum install -y java-17-amazon-corretto-devel

# Install Git (for JGit to work)
sudo yum install -y git

# Create app directory
sudo mkdir -p /opt/codebase-qa
sudo chown ec2-user:ec2-user /opt/codebase-qa

# Create temp directory for repo cloning
mkdir -p /tmp/codebase-qa
```

---

## 3. Deploying the Backend

### 3.1 Build the JAR

```bash
cd backend
mvn clean package -DskipTests
# Output: target/backend-0.0.1-SNAPSHOT.jar
```

### 3.2 Copy to EC2

```bash
scp -i your-key.pem target/backend-0.0.1-SNAPSHOT.jar \
  ec2-user@EC2_PUBLIC_IP:/opt/codebase-qa/app.jar
```

### 3.3 Create Systemd Service

SSH into EC2 and create `/etc/systemd/system/codebase-qa.service`:

```ini
[Unit]
Description=Codebase QA Backend
After=network.target

[Service]
User=ec2-user
WorkingDirectory=/opt/codebase-qa
ExecStart=/usr/bin/java -jar /opt/codebase-qa/app.jar --spring.profiles.active=prod
Restart=always
RestartSec=10

# Environment variables (loaded from SSM at startup via a wrapper script)
Environment=DB_HOST=your-rds-endpoint.rds.amazonaws.com
Environment=DB_PORT=5432
Environment=DB_NAME=codebaseqa
Environment=DB_USERNAME=postgres
Environment=AWS_REGION=us-east-1
Environment=SQS_QUEUE_URL=https://sqs.us-east-1.amazonaws.com/ACCOUNT_ID/codebase-qa-indexing
Environment=SQS_DLQ_URL=https://sqs.us-east-1.amazonaws.com/ACCOUNT_ID/codebase-qa-indexing-dlq
Environment=FRONTEND_URL=https://your-app.vercel.app
Environment=INDEXING_TEMP_DIR=/tmp/codebase-qa

[Install]
WantedBy=multi-user.target
```

**Note:** For secrets (DB_PASSWORD, JWT_SECRET, etc.), use a startup script that fetches from SSM:

```bash
#!/bin/bash
# /opt/codebase-qa/start.sh
export DB_PASSWORD=$(aws ssm get-parameter --name /codebase-qa/db-password --with-decryption --query Parameter.Value --output text)
export JWT_SECRET=$(aws ssm get-parameter --name /codebase-qa/jwt-secret --with-decryption --query Parameter.Value --output text)
export GITHUB_CLIENT_ID=$(aws ssm get-parameter --name /codebase-qa/github-client-id --with-decryption --query Parameter.Value --output text)
export GITHUB_CLIENT_SECRET=$(aws ssm get-parameter --name /codebase-qa/github-client-secret --with-decryption --query Parameter.Value --output text)
export GEMINI_API_KEY=$(aws ssm get-parameter --name /codebase-qa/gemini-api-key --with-decryption --query Parameter.Value --output text)
export REDIS_URL=$(aws ssm get-parameter --name /codebase-qa/redis-url --with-decryption --query Parameter.Value --output text)

java -jar /opt/codebase-qa/app.jar --spring.profiles.active=prod
```

Then update the service to use: `ExecStart=/bin/bash /opt/codebase-qa/start.sh`

### 3.4 Start the Service

```bash
sudo systemctl daemon-reload
sudo systemctl enable codebase-qa
sudo systemctl start codebase-qa

# Check logs
sudo journalctl -u codebase-qa -f
```

---

## 4. Deploying the Frontend (Vercel)

### 4.1 Push Frontend to GitHub

Make sure the `frontend/` directory is in a GitHub repo.

### 4.2 Connect to Vercel

1. Go to [vercel.com](https://vercel.com) and sign in with GitHub
2. Click "New Project" → Import your repo
3. Set the root directory to `frontend`
4. Framework preset: Vite
5. Build command: `npm run build`
6. Output directory: `dist`

### 4.3 Set Environment Variables in Vercel

```
VITE_API_URL = http://EC2_PUBLIC_IP:8080
```

(Later, when you add a domain + HTTPS, update this to `https://api.yourdomain.com`)

### 4.4 Deploy

Vercel auto-deploys on every push to main. Your frontend will be at `https://your-project.vercel.app`.

---

## 5. GitHub OAuth App Setup

1. Go to GitHub → Settings → Developer Settings → OAuth Apps → New OAuth App
2. Fill in:
   - **Application name:** Codebase Q&A
   - **Homepage URL:** `https://your-project.vercel.app`
   - **Authorization callback URL:** `http://EC2_PUBLIC_IP:8080/api/auth/github/callback`
3. Save the Client ID and Client Secret
4. Store them in SSM Parameter Store (done in step 2.5)

---

## 6. Setting Up HTTPS (Optional but Recommended)

For a portfolio project, HTTP is fine. But if you want HTTPS:

### Option A: Use Nginx as reverse proxy with Let's Encrypt

```bash
# Install Nginx and Certbot on EC2
sudo yum install -y nginx certbot python3-certbot-nginx

# Configure Nginx (/etc/nginx/conf.d/codebase-qa.conf)
server {
    listen 80;
    server_name api.yourdomain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;  # Important for SSE!
    }
}

# Get SSL certificate
sudo certbot --nginx -d api.yourdomain.com
```

### Option B: Just use HTTP for the portfolio demo

Update the Vercel env var to point to `http://EC2_PUBLIC_IP:8080` and accept the browser warning.

---

## 7. Alternative: Deploy Without AWS (Simpler)

If you don't want to manage AWS, use these free alternatives:

| Component | Service | Setup |
|-----------|---------|-------|
| Backend | [Render](https://render.com) | Connect GitHub, auto-deploy |
| Database | [Neon](https://neon.tech) | Free PostgreSQL with pgvector |
| Queue | Replace SQS with in-process queue | Use a `@Scheduled` method polling a `jobs` table |
| Redis | [Upstash](https://upstash.com) | Same as AWS setup |
| Frontend | Vercel | Same as above |

The architecture stays the same — you just swap SQS for a Postgres-backed job queue (poll the `indexing_jobs` table for QUEUED jobs).
