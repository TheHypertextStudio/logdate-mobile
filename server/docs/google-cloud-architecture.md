# Google Cloud Architecture for LogDate Cloud

This document outlines the recommended Google Cloud services for hosting LogDate Cloud, while maintaining compatibility with self-hosted deployments.

## Architecture Overview

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Client Apps   │────│   Cloud Run     │────│   PostgreSQL    │
│ (iOS/Android/   │    │  (LogDate API)  │    │ (Neon for first │
│  Desktop/Web)   │    │                 │    │  party)         │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                       ┌─────────────────┐
                       │ Cloud Storage   │
                       │ (Media Files)   │
                       └─────────────────┘
```

## Core Infrastructure

### Compute & API

- **Cloud Run**: Containerized LogDate API server
  - Auto-scaling based on request volume
  - Pay-per-use pricing model
  - Supports WebSocket connections for real-time sync
  - Easy deployment from container images

### Database

- **PostgreSQL**: Primary data storage, reached through the standard `DATABASE_URL`,
  `DATABASE_USER`, and `DATABASE_PASSWORD` settings
  - LogDate Cloud staging and production use Neon, one isolated database per environment
    (see [Staging and production configuration](../../docs/runbook/staging-production-configuration.md#database-contract))
  - A self-hosted server can use any PostgreSQL service that satisfies the same settings
  - A Cloud SQL instance also works: set `INSTANCE_CONNECTION_NAME` and `DB_NAME` instead of
    `DATABASE_URL`, and the server connects through the Cloud SQL connector. The turnkey
    bootstrap command provisions this variant for a new single-instance deployment
  - The connection pool is sized to Cloud Run's per-instance request concurrency keeps no
    idle floor and releases idle connections after five minutes, which lets a serverless database suspend its compute after traffic stops
  - In production, Flyway migrations run as an explicit step before a revision receives
    traffic, not when the server starts

### Storage

- **Cloud Storage**: Media and asset storage (private bucket, optional signed URLs)
  - Global CDN integration
  - Lifecycle management for cost optimization
  - Signed URLs for secure direct uploads
  - Versioning and retention policies

## AI/Intelligence Services

### Content Analysis

- **Vertex AI**: Content summarization and insights
  - Custom models for journal analysis
  - AutoML for personalized features
  - Batch prediction for rewind generation

### Media Processing

- **Speech-to-Text API**: Audio transcription
  - Support for multiple languages
  - Real-time and batch processing
  - Custom vocabulary for better accuracy

- **Natural Language AI**: Text analysis
  - Entity extraction (people, places)
  - Sentiment analysis
  - Content classification

- **Vision AI**: Image analysis
  - Object detection and labeling
  - OCR for text in images
  - Content moderation

## Real-time & Sync

### Event Processing

- **Cloud Pub/Sub**: Event-driven architecture
  - Reliable message delivery
  - Fan-out to multiple subscribers
  - Dead letter queues for error handling

### Functions

- **Cloud Functions**: Serverless processing
  - Media processing triggers
  - Background job processing
  - Webhook handlers

## Security & Identity

### Authentication

- **Firebase Auth**: User authentication
  - Integration with existing client apps
  - Multi-provider support (Google, Apple, email)
  - Custom claims for role-based access

### Access Control

- **Cloud IAM**: Service-to-service authentication
  - Fine-grained permissions
  - Service accounts for secure API access
  - Audit logging

### Encryption

- **Cloud KMS**: Key management
  - Customer-managed encryption keys
  - Automatic key rotation
  - Hardware security modules
  - Optional CMEK for media uploads via `GCS_MEDIA_KMS_KEY`

## Monitoring & Operations

### Observability

- **Cloud Monitoring**: Application metrics
  - Custom dashboards
  - Alerting policies
  - SLA monitoring

- **Cloud Logging**: Centralized logging
  - Structured logging
  - Log-based metrics
  - Export to BigQuery for analysis

- **Cloud Trace**: Distributed tracing
  - Request flow visualization
  - Performance bottleneck identification
  - Latency analysis

### Error Management

- **Cloud Error Reporting**: Error tracking
  - Real-time error notifications
  - Error grouping and analysis
  - Integration with issue tracking

## Networking

### Load Balancing

- **Cloud Load Balancing**: Global traffic distribution
  - HTTPS termination
  - WebSocket support
  - Health checks

### CDN

- **Cloud CDN**: Global content delivery
  - Edge caching for media files
  - Cache invalidation
  - Compression and optimization

## Cost Optimization

### Resource Management

- **Cloud Run**: Pay-per-request pricing
- **Neon**: Compute suspends when idle, so an unused environment costs little
- **Cloud Storage**: Lifecycle policies for archiving
- **Committed Use Discounts**: For predictable workloads

### Monitoring

- **Cloud Billing**: Cost tracking and budgets
- **Recommender**: Cost optimization suggestions
- **Resource quotas**: Prevent runaway costs

## Self-Hosting Compatibility

The architecture is designed to be cloud-agnostic:

### Containerization

- Docker containers for all services
- Kubernetes manifests available
- Helm charts for easy deployment

### Configuration

- Environment variable-based configuration
- Support for local storage backends
- Optional cloud service integration

### Database Flexibility

- PostgreSQL for every deployment, with schema changes applied by Flyway migrations
- In-memory repositories only outside production, only when
  `LOGDATE_ALLOW_INMEMORY_FALLBACK=true`; nothing persists across a restart

### Storage Abstraction

- Local filesystem storage option
- S3-compatible storage interface
- MinIO for self-hosted object storage

## Deployment Pipeline

### CI/CD

- **Terraform (endgame)**: IaC for Cloud Run, IAM, and Artifact Registry
- **GitHub Actions + gcloud (fallback)**: Emergency builds and deploys
- **Artifact Registry**: Container image storage

### Testing

- **Cloud Build**: Automated testing
- **Container Analysis**: Security scanning
- **Performance testing**: Load testing with realistic data

## Disaster Recovery

### Backup Strategy

- **PostgreSQL**: Backups and point-in-time restoration come from the database provider
- **Cloud Storage**: Cross-region replication

### High Availability

- **Multi-zone deployment**: Regional redundancy
- **Health checks**: Automatic failover
- **Circuit breakers**: Graceful degradation

## Migration Path

### From Self-hosted to Cloud

1. Export data from self-hosted instance
2. Import to the cloud database
3. Migrate media to Cloud Storage
4. Update client configuration
5. Verify data integrity

### From Cloud to Self-hosted

1. Export data via API
2. Deploy self-hosted instance
3. Import data and media
4. Update client endpoints
5. Verify functionality

This architecture provides a robust, scalable foundation for LogDate Cloud while maintaining the flexibility for users to self-host their own instances.
