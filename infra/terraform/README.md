# LogDate Cloud Run Terraform

This folder codifies the production GCP stack for LogDate Cloud. It is intended as the
long-term source of truth. GitHub Actions + gcloud remains the operational fallback
for emergency deploys.

## What this creates
- Cloud Run service (Gen2) with configurable limits and env vars
- Artifact Registry repository (optional)
- Runtime service account (least-privilege target)
- GitHub Actions Workload Identity Federation (optional)
- Secret Manager secrets (optional, placeholder only)
- GCS bucket for media (optional)
- Cloud Run domain mapping (optional, requires verified domain)
- Global external Application Load Balancer that serves the repository event stream from its own
  HTTP/1 service (optional, set `enable_load_balancer`; see [Load balancer](#load-balancer))
- Cloud Monitoring uptime check and alert for AT Protocol handle resolution (optional, set
  `handle_resolution_monitor`; see `docs/reference/handle-resolution.md`)

## Required inputs
- `project_id`
- `cloud_run_image` (e.g. `us-central1-docker.pkg.dev/PROJECT/logdate/logdate-server:latest`)

## Quick start
```bash
cd infra/terraform
terraform init
terraform apply \
  -var "project_id=YOUR_PROJECT" \
  -var "cloud_run_image=us-central1-docker.pkg.dev/YOUR_PROJECT/logdate/logdate-server:latest" \
  -var "github_repo=owner/repo"
```

## Turnkey bootstrap
For a brand-new single-instance deployment, use the opinionated bootstrap command instead of
manually creating the project, Cloud SQL instance, bucket, secrets, and Cloud Run service.

```bash
./run deploy:bootstrap --yes --project-id YOUR_PROJECT_ID
```

What it does:
- creates or reuses the GCP project and links billing
- creates a remote Terraform state bucket
- provisions Artifact Registry, runtime/deploy identities, Secret Manager, GCS media storage, and Cloud SQL
- generates first-install secrets outside Terraform state and uploads them to Secret Manager
- builds and pushes the initial server image
- deploys Cloud Run and rewrites local bootstrap config with the managed service URL
- stores instance state in `.logdate/deploy/<project-id>/`

If `gh` is authenticated and has repo admin access, bootstrap also writes the GitHub deploy
secrets and repository variables used by the server deploy workflows, so CI can keep shipping
image-only deploys without a committed `*.tfvars` file.

## Environment configuration
This repo treats `*.tfvars` as the shared source of truth for both Terraform and
runbooks (including `scripts/deploy-cloud-run.sh`) and CI. Keep staging/production
config in HCL so automation can read it consistently.

```bash
terraform apply -var-file=staging.tfvars
```

Set environment variables with `cloud_run_env` and secrets with `cloud_run_secret_env`.
The module automatically injects `HOST`, `GCS_PROJECT_ID`, and optionally
`GCS_BUCKET_NAME`, `WEBAUTHN_RP_ID`, `WEBAUTHN_ORIGIN`.

Cloud Run runtime tuning is configured with `request_concurrency`, `cpu_idle`,
and `startup_cpu_boost`. The service template also wires `/health` startup and
liveness probes on port `8080` so deploys fail faster when a revision cannot boot.

Terraform ignores image changes by default so CI can update images without causing drift.
To pin images explicitly, remove the `ignore_changes` block from `infra/terraform/main.tf`.

Buckets and secrets are protected with `prevent_destroy = true` by default. To allow
deletion, remove those lifecycle blocks in `infra/terraform/main.tf`.

Example `staging.tfvars`:
```hcl
project_id      = "logdate-staging"
region          = "us-central1"
service_name    = "logdate-server-staging"
cloud_run_image = "us-central1-docker.pkg.dev/logdate-staging/logdate/logdate-server:latest"
webauthn_rp_id  = "staging.logdate.app"
webauthn_origin = "https://app.staging.logdate.app"

enable_github_oidc = true
github_repo        = "your-org/logdate"

create_gcs_bucket = true
gcs_bucket_name   = "logdate-media-staging"

request_concurrency = 16
cpu_idle            = true
startup_cpu_boost   = true

cloud_run_env = {
  AUTO_MIGRATE = "true"
}

cloud_run_secret_env = {
  DATABASE_URL = { secret_id = "logdate-db-url" }
  DATABASE_USER = { secret_id = "logdate-db-user" }
  DATABASE_PASSWORD = { secret_id = "logdate-db-password" }
  JWT_SECRET = { secret_id = "logdate-jwt-secret" }
}

create_secrets = true
```

Use `production.tfvars.example` as a template for production.
Commit only non-sensitive fields; keep secrets in Secret Manager.
CI expects a real `production.tfvars` to exist for tag-based deployments unless
`LOGDATE_DEPLOY_SOURCE=repo_vars` is set for a bootstrap-managed instance.

## Secrets workflow
- `create_secrets = true` creates empty Secret Manager secrets only.
- Add values separately:
  ```bash
  echo -n "value" | gcloud secrets versions add logdate-db-url --data-file=-
  ```
- Ensure the runtime service account has `roles/secretmanager.secretAccessor`.
- The turnkey bootstrap command uploads first-install secret versions with `gcloud` after
  Terraform creates the secret containers, so plaintext secret material does not land in
  Terraform state.

## State backend (recommended)
Use a remote state backend for staging/production. Copy `backend.tf.example` to
`backend.tf`, set a GCS bucket, and re-run `terraform init` with migration.

## Domain mapping
Set `enable_domain_mapping = true` and `domains = ["cloud.logdate.app", "logdate.hypertext.studio"]`
after verifying ownership of each entry in Google Search Console. One
`google_cloud_run_domain_mapping` is created per list entry, all pointing at the same
Cloud Run service. SSL provisioning per domain can take 15–30 minutes.

## Load balancer
The main service uses end-to-end HTTP/2, because Cloud Run limits HTTP/1 requests to 32 MiB and
encrypted recordings and backups are larger. Cloud Run does not pass WebSockets to a service set up
that way, and the AT Protocol repository event stream (`com.atproto.sync.subscribeRepos`) is a
WebSocket. Relays expect it on the same hostname as everything else, and a Cloud Run domain mapping
can only send a whole hostname to one service.

`enable_load_balancer = true` puts a global external Application Load Balancer in front of every
entry in `domains`. It sends `/xrpc/com.atproto.sync.subscribeRepos` to `<service_name>-repo-events`,
a second service that runs the same image over HTTP/1, and every other path to the main service. The
deploy workflow creates and updates that second service from the same deployment contract whenever
`ATPROTO_FIREHOSE_ENABLED` is `"true"`; Terraform only routes to it. The certificate comes from
Certificate Manager and is authorized through DNS, so it is issued before any traffic moves.

Moving a hostname from its domain mapping to the load balancer:

1. Apply with `enable_load_balancer = true`, then read the records to publish:
   ```bash
   terraform output -json load_balancer_dns_records
   ```
2. In the Cloudflare `logdate.app` zone, add the `certificate_authorization` CNAME as DNS only (grey
   cloud). Wait until the certificate is active:
   ```bash
   gcloud certificate-manager certificates describe <service_name>-lb --project <project_id> \
     --format='value(managed.state)'
   ```
3. Replace the hostname's CNAME to `ghs.googlehosted.com` with the `traffic` A record, also DNS only.
   Proxying through Cloudflare would cap uploads at Cloudflare's request size limit.
4. Check `https://<domain>/health`, a WebSocket to the event stream (see the
   [repository event stream runbook](../../docs/runbook/repository-event-stream.md)), and a deploy's
   smoke proof. Then set `enable_domain_mapping = false` to delete the unused domain mapping.

Staging (`logdate-dev`) was not created with Terraform, so its state in
`gs://logdate-dev-terraform-state` (prefix `logdate/staging`) holds only the load balancer. Apply it
with a `backend.tf` copied from `backend.tf.example` for that bucket and prefix, and target the load
balancer so Terraform does not try to create what already exists:
```bash
terraform apply -var-file=staging.tfvars \
  -target=google_compute_global_forwarding_rule.load_balancer_https \
  -target=google_certificate_manager_certificate_map_entry.load_balancer
```

## Fallback (manual) deploy
Use `scripts/deploy-cloud-run.sh` for emergency deploys with gcloud + Docker. This keeps
Terraform as the endgame while still enabling manual intervention.

By default the script only updates the image (no config drift). To bootstrap or fully
re-apply runtime config from the shared tfvars, run with `CONFIG_MODE=full`.
Image-only mode expects the service to already exist.
