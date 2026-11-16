# LogDate Cloud production readiness (GCP)

This document defines the production-ready setup for LogDate Cloud on GCP.
Terraform is the long-term source of truth. GitHub Actions + gcloud remains
available for emergency deploys and manual intervention.

## Environments

- **Staging (default)**: Deploys on main branch CI success.
- **Production**: Deploys only from tags.
- **Bootstrap-managed single instance**: Deploys from the managed Cloud Run URL and uses GitHub
  repository variables instead of committed `staging.tfvars` / `production.tfvars`.

The first-party staging and production environments are governed by the deployment contract
in [Staging and production configuration](../../docs/runbook/staging-production-configuration.md):
committed tfvars rendered into one contract, Neon PostgreSQL, exact secret versions, and a
no-traffic candidate that must pass a smoke test before it receives traffic. The rest of this
page covers Terraform and the bootstrap-managed single instance.

## Turnkey bootstrap

Use this when you want a fresh first-party instance with the least manual setup:

```bash
./run deploy:bootstrap --yes --project-id YOUR_PROJECT_ID
```

The bootstrap command is intentionally opinionated:

- GCP-only
- Cloud SQL PostgreSQL instead of an external database, reached through
  `INSTANCE_CONNECTION_NAME` and `DB_NAME`
- managed Cloud Run URL instead of a custom domain for first install
- passkey auth ready immediately, Google sign-in optional until client IDs are added
- generated deployment metadata stored under `.logdate/deploy/<project-id>/`

If `gh` is authenticated, bootstrap also sets the GitHub Actions auth secrets and the repository
variables used for ongoing image deploys. That path remains OIDC-based; it does not create
service account keys.

## IaC (endgame)

Terraform lives in `infra/terraform`.

### Terraform workflow

1. Configure `staging.tfvars` / `production.tfvars` with:
   - `project_id`, `region`, `service_name`, `cloud_run_image`
   - `github_repo` for Workload Identity Federation
   - `cloud_run_env` + `cloud_run_secret_env`
   - Commit `production.tfvars` so tag-based deploys can read it in CI.
2. Initialize and apply:
   ```bash
   cd infra/terraform
   terraform init
   terraform apply -var-file=staging.tfvars
   ```
3. Insert secret values into Secret Manager:
   ```bash
   echo -n "value" | gcloud secrets versions add logdate-db-url --data-file=-
   ```
   First-party contracts mount each secret by exact version number, so after adding a version,
   set that number in the environment's tfvars.
4. For staging/production, use a remote state backend (see `infra/terraform/backend.tf.example`).

### Recommended variables

- `webauthn_rp_id` and `webauthn_origin`: the host people sign in on and its HTTPS origin. The
  first-party deployment contract requires production to use `logdate.app`, requires a staging
  relying-party ID that is neither production's nor an ancestor of it, and rejects production
  browser origins in staging. The first-party values are in the
  [configuration table](../../docs/runbook/staging-production-configuration.md#server-environments).
- `create_gcs_bucket`: true for managed media storage
- `cloud_run_secret_env`: map of env var name -> secret ID
- `request_concurrency`: tune per-environment instead of relying on Cloud Run's default 80; this repo now uses 16 as the starting point for the JVM API.
- `startup_cpu_boost`: keep enabled to reduce cold-start latency on scale-from-zero revisions.
- `cpu_idle`: set explicitly; use `true` to preserve request-only CPU allocation when custom resource limits are configured.
- `SYNC_MEDIA_SIGNED_URLS`: true to return short-lived GCS signed URLs for media downloads
- `SYNC_MEDIA_SIGNED_URL_TTL_HOURS`: signed URL TTL in hours (1-24)

## GitHub Actions (fallback and emergency)

The server deployment workflows are:

- `.github/workflows/deploy-server-staging.yml` for staging rollouts after CI.
- `.github/workflows/deploy-server-production.yml` for production `server-v*` tag rollouts.
- `.github/workflows/deploy-server-cloud-run.yml` for the shared Cloud Run rollout implementation.

The workflow reads `infra/terraform/<env>.tfvars` for project/service metadata
when those files exist. Bootstrap-managed instances can instead use repository variables for
project/service metadata and only require Workload Identity secrets for authentication.

Required GitHub Secrets:

- `GCP_WORKLOAD_IDENTITY_PROVIDER`
- `GCP_SERVICE_ACCOUNT`

Bootstrap-managed repository variables:

- `LOGDATE_DEPLOY_SOURCE=repo_vars`
- `LOGDATE_PROJECT_ID`
- `LOGDATE_REGION`
- `LOGDATE_SERVICE_NAME`
- `LOGDATE_ARTIFACT_REGISTRY_REPO`
- `LOGDATE_RUNTIME_SERVICE_ACCOUNT`

When `LOGDATE_DEPLOY_SOURCE=repo_vars`, the workflow prefers those repository variables even if
checked-in `staging.tfvars` / `production.tfvars` files still exist.

## Manual gcloud deploy

Use `scripts/deploy-cloud-run.sh` when Terraform or CI is unavailable.
This script defaults to image-only deploys to avoid config drift. Use `CONFIG_MODE=full`
to re-apply runtime config from `infra/terraform/<env>.tfvars`.
Image-only mode expects the service to already exist.
The script does not apply the deployment contract's checks (closed key set, certificate rules,
pinned secret versions) or the candidate smoke test. For first-party staging and production,
dispatch the deploy workflow instead.

## Health checks

- Runtime `/health` endpoint is used by deploy verification.
- Cloud Run performs startup/readiness checks on the exposed port.
- Terraform-managed services also use `/health` for startup and liveness probes on port `8080`.

## Post-deploy validation

- Verify `/health` for the Cloud Run URL and custom domain. The CI workflow and
  `scripts/deploy-cloud-run.sh` now retry health checks instead of failing on a
  single cold-start window.
- Validate WebAuthn RP ID/origin matches the deployed domain.
- Confirm database connectivity and media uploads.
- If `SYNC_MEDIA_SIGNED_URLS` is enabled, verify download URLs are signed and time-limited.
