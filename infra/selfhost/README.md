# LogDate Cloud on one machine

This directory runs LogDate Cloud on a single Linux or macOS machine with Docker: PostgreSQL,
the server, and [Caddy](https://caddyserver.com/) for HTTPS. Media and backups are stored on the
machine's disk. It is the same server image Cloud Run runs, configured without Google Cloud.

Use it when Google Cloud is unavailable. The apps keep using `https://cloud.logdate.app`, so
moving the domain here needs no app release.

## What differs from the Cloud Run deployment

| Cloud Run                                  | This machine                                                   |
| ------------------------------------------ | -------------------------------------------------------------- |
| Neon PostgreSQL                            | PostgreSQL in Docker, on a named volume                        |
| Media and backups in Cloud Storage         | Files under the `blob-data` volume (`LOGDATE_BLOB_STORAGE_DIR`) |
| Secrets in Secret Manager                  | `secrets.env`, mode 600, created by `generate-secrets.sh`      |
| Migrations run by the deploy workflow      | The server applies them at startup (`AUTO_MIGRATE=true`)       |
| `BILLING_PROVIDER=play`                    | `disabled`: every account is unlimited and nothing is sold     |
| `LOGDATE_EXPECT_FIRST_PARTY=true`          | Unset, because it requires Cloud Storage and billing           |
| Scales to more instances under load        | One server process                                             |

Every other production check still runs: `LOGDATE_ENV=production` refuses to start with weak
secrets, a missing health token, or passkey origins outside `logdate.app`.

## Requirements

- Docker with Compose v2.
- At least 2 CPU cores, 4 GB of memory, and disk for everyone's media. An Oracle Cloud
  Always Free Ampere A1 instance (4 cores, 24 GB, 200 GB) fits.
- Ports 80 and 443 reachable from the internet, so Caddy can get a certificate.
- A DNS record for `cloud.logdate.app` that points straight at the machine, with Cloudflare's
  proxy **off** (DNS only, grey cloud). Through Cloudflare's proxy, uploads over 100 MB fail on
  the free plan, and Caddy cannot complete its certificate challenge.

## First start

```bash
git clone https://github.com/TheHypertextStudio/logdate-mobile.git
cd logdate-mobile/infra/selfhost
./generate-secrets.sh   # creates logdate.env and secrets.env
./deploy.sh             # builds the image, starts everything, waits for /health
```

Copy `secrets.env` to a password manager right away. `SERVER_ENCRYPTION_KEY` and
`ATPROTO_SIGNING_KEY_KEK` encrypt stored journals and identity keys; without them a backup of
the database is unreadable.

`deploy.sh` records the commit it built as `RELEASE_VERSION` in `.env`, which every later
`docker compose` command in this directory reads, then waits for the server to report
`db_connected: true`. Caddy gets the certificate on
the first HTTPS request after DNS points here; check it from anywhere:

```bash
curl https://cloud.logdate.app/api/v1/server/info
```

### Moving from Cloud Run

If the Cloud Run secrets are still readable, put their values in `secrets.env` before the first
start instead of generating new ones, and restore the database from Neon with `pg_restore`.
Accounts and journals then carry over. Media in Cloud Storage does not move with this bundle;
copy the bucket's objects into the `blob-data` volume under the same paths.

If the secrets are gone, the old data cannot be decrypted. Start with an empty database;
existing accounts sign up again.

## Updating

```bash
git pull
./deploy.sh
```

The server is down for the few seconds the new container takes to start.

## Backups

`backup.sh` writes a database dump and an archive of the media to `./backups` and keeps the
newest seven. Run it daily from cron and copy `./backups` off the machine, for example to a
Cloudflare R2 bucket with `rclone`:

```cron
15 3 * * * cd /home/ubuntu/logdate-mobile/infra/selfhost && ./backup.sh && rclone sync backups r2:logdate-backups
```

To restore, stop the server, `pg_restore --clean` the dump into the `postgres` container, and
extract the media archive into the `blob-data` volume.

## Behind Cloudflare Tunnel instead

On a machine without public ports, such as a Mac at home, run
[`cloudflared`](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
in place of Caddy: remove the `caddy` service's `ports`, point the tunnel at `http://server:8080`
on the `internal` network, and set `TRUSTED_PROXY_HOPS=1` in `logdate.env`. Uploads through the
tunnel are capped at 100 MB on Cloudflare's free plan, so large backups fail, and the server is
down whenever the machine or its connection is.
