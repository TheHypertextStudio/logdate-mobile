# `:server`

**Self-hostable backend API and services using Ktor**

## Overview

Ktor-based server providing API endpoints, data synchronization services, and backend functionality for the LogDate application. Designed to be easily containerizable and self-hostable, allowing users to run their own instance of LogDate Cloud with full data ownership and privacy control.

LogDate's purpose is **a home to document, reflect on, and share memories**. The server supports
that experience through durable data, media, and account services. Journals and entries are
technical concepts within that purpose, not the product's primary market position. See the
[product positioning](../docs/product-info/product-positioning.md).

**Key Design Principles:**
- **Self-hostable**: Complete Docker containerization for easy deployment
- **Privacy-first**: Users maintain full control over their data
- **Cloud-optional**: Can run entirely offline or with minimal cloud dependencies
- **Configuration-driven**: Flexible configuration for different deployment scenarios

## Architecture

```
Server Module
├── REST API Endpoints
├── Authentication & Authorization
├── Data Synchronization
├── Database Integration
└── Service Layer
```

## Key Components

### Core Server
- `Application.kt` - Main server application entry point
- API route definitions and handlers
- Middleware and request/response processing
- Database connection and management

### Service Areas
- User authentication and session management
- Journal and entry synchronization
- File upload and media management
- Push notifications and real-time updates
- Analytics and usage tracking

## Features

### REST API
- RESTful endpoint design
- JSON request/response handling
- API versioning support
- Rate limiting and throttling
- Request validation and sanitization

### Authentication
- JWT-based authentication
- OAuth integration (Google, Apple)
- Session management
- Role-based access control
- API key management

### Data Synchronization
- Real-time data sync
- Conflict resolution
- Incremental sync optimization
- Offline sync support
- Multi-device coordination

### File Management
- Media upload and storage
- Image processing and optimization
- CDN integration
- File versioning
- Storage quota management

## Development

### Configuration

See **[Environment Variables Documentation](docs/environment-variables.md)** for complete configuration reference.
See the **API reference** at `/docs` on any running server (`http://localhost:8765/docs` locally) for every endpoint with examples; [`docs/openapi.md`](docs/openapi.md) explains how it is maintained. [`docs/auth-v1-api.md`](docs/auth-v1-api.md) and [`docs/sync-v1-api.md`](docs/sync-v1-api.md) keep the rules that span endpoints.
See **[Audit Schema](docs/audit-schema.md)** for centralized audit categories and keys.

**Quick start:**
1. Copy `../local.properties.example` to `../local.properties`
2. Fill in required values (DATABASE_URL, JWT_SECRET, etc.)
3. For production, see [Google Cloud Production Setup](docs/google-cloud-production.md)

### Running Tests
```bash
# Run all server tests
./gradlew :server:test

# Run specific test classes
./gradlew :server:test --tests "app.logdate.server.passkeys.*"
./gradlew :server:test --tests "app.logdate.server.routes.*"
./gradlew :server:test --tests "app.logdate.server.auth.*"

# Run individual test
./gradlew :server:test --tests "app.logdate.server.passkeys.PasskeyServiceTest.generateRegistrationOptions creates valid options"

# Test compilation only (useful when main code has issues)
./gradlew :server:compileTestKotlin
```

### Available Test Suites
- **Passkey Tests**: WebAuthn authentication, challenge validation, credential verification
- **Route Tests**: HTTP endpoint integration tests, request/response validation
- **Auth Tests**: Account creation workflows, session management
- **Security Tests**: WebAuthn compliance, security constraint validation

## Dependencies

### Core Dependencies
- **Ktor Server**: Web framework
- **Kotlinx Serialization**: JSON handling
- **Kotlinx Coroutines**: Async processing
- **Koin**: Dependency injection
- **Logback**: Logging framework

### Shared Modules
- `:shared:model` - Shared data models
- `:shared:config` - Configuration constants

### Database
- PostgreSQL or similar relational database
- Database migration tools
- Connection pooling
- Query optimization

### External Services
- Cloud storage (AWS S3, Google Cloud)
- Push notification services
- Email service integration
- Analytics and monitoring tools

## API Architecture

### Endpoint Structure
```
/api/v1/
├── /server/info     # What this deployment is and can do
├── /plans           # Plans on offer
├── /auth            # Sign-up, sign-in, tokens, the signed-in account
├── /identity        # AT Protocol signing keys and PLC history
├── /contents        # Entries (sync)
├── /journals        # Journals (sync)
├── /associations    # Entry-to-journal links (sync)
├── /drafts          # In-progress entries (sync)
├── /media           # Photos, video, audio
├── /backups         # Encrypted device backups
├── /quota           # Storage allowance and usage
├── /transcription   # Cloud speech-to-text sessions
└── /resources       # Shared-link resolution
/oauth/, /xrpc/, /.well-known/   # AT Protocol OAuth, XRPC and discovery
```
The full contract with examples is at `/docs`.

### Request Flow
```
Client Request
    ↓
Authentication
    ↓
Validation
    ↓
Business Logic
    ↓
Database Operations
    ↓
Response Formatting
```

## Security Features

### Authentication & Authorization
- Multi-factor authentication support
- Secure password handling
- Token refresh mechanisms
- Permission-based access control
- API rate limiting

### Data Protection
- HTTPS enforcement
- Data encryption at rest and in transit
- Input validation and sanitization
- SQL injection prevention
- CORS configuration

### Privacy Compliance
- GDPR compliance features
- Data anonymization tools
- User data export/deletion
- Audit logging
- Privacy policy enforcement

## Deployment Options

### Self-Hosted Deployment (Recommended)
- **Docker Compose**: Single-file deployment for individuals
- **Docker Swarm**: Multi-node deployment for small teams
- **Kubernetes**: Enterprise-grade orchestration
- **Direct Installation**: Native deployment on Linux/macOS/Windows

### Cloud Deployment (Optional)
- Google Cloud Run (recommended for managed hosting)
- AWS ECS/Fargate
- Azure Container Instances
- Any container-compatible hosting provider

### Containerization
```dockerfile
# Multi-stage build for optimized container size
FROM gradle:7-jdk17 AS build
FROM eclipse-temurin:17-jre-alpine AS runtime
# Optimized for self-hosting with minimal dependencies
```

### Environment Management
- **Configuration**: Environment variables and config files
- **Secrets**: Local file-based or external secret managers
- **Database**: Embedded SQLite for single-user or PostgreSQL for multi-user
- **Storage**: Local filesystem or S3-compatible storage
- **AI Services**: Optional integration with local or cloud AI providers

## Monitoring & Observability

### Logging
- Structured logging with Logback
- Request/response logging
- Error tracking and alerting
- Performance metrics
- Security event logging

### Metrics
- Application performance monitoring
- Database query performance
- API endpoint analytics
- Resource usage tracking
- Business metrics

## TODOs

### Core API Features
- [x] Publish a complete, newcomer-friendly API reference with the self-hosted Scalar page at `/docs`
- [ ] Add API rate limiting and throttling
- [ ] Implement advanced authentication (OAuth, SSO)
- [ ] Add server monitoring and metrics collection
- [ ] Implement server-side analytics and insights
- [ ] Add API versioning and deprecation management
- [ ] Implement server security hardening
- [ ] Add server deployment automation

### Data & Sync
- [ ] Implement real-time WebSocket connections
- [ ] Add advanced conflict resolution algorithms
- [ ] Implement server-side data validation
- [ ] Add data backup and disaster recovery
- [ ] Implement database performance optimization
- [ ] Add server-side search and indexing
- [ ] Implement data archival and retention policies
- [ ] Add server-side caching strategies

### Scalability & Performance
- [ ] Implement horizontal scaling support
- [ ] Add database sharding and partitioning
- [ ] Implement CDN integration for static assets
- [ ] Add server-side performance profiling
- [ ] Implement load testing and capacity planning
- [ ] Add server resource optimization
- [ ] Implement microservices architecture migration
- [ ] Add server-side A/B testing framework

### Security & Compliance
- [ ] Implement advanced security monitoring
- [ ] Add penetration testing automation
- [ ] Implement GDPR compliance automation
- [ ] Add security audit logging
- [ ] Implement data encryption key management
- [ ] Add security incident response automation
- [ ] Implement compliance reporting
- [ ] Add privacy-preserving analytics
