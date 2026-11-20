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
- With Caddy (`LOGDATE_EDGE=caddy`, the default): ports 80 and 443 reachable from the internet,
  and a DNS record for `cloud.logdate.app` that points straight at the machine with Cloudflare's
  proxy **off** (DNS only, grey cloud). Through Cloudflare's proxy, uploads over 100 MB fail on
  the free plan, and Caddy cannot complete its certificate challenge.
- With a Cloudflare Tunnel (`LOGDATE_EDGE=tunnel`): no open ports; see
  [On a Mac at home](#on-a-mac-at-home-through-cloudflare-tunnel).

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

## On a Mac at home, through Cloudflare Tunnel

A [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
lets a machine with no open ports serve `cloud.logdate.app`: `cloudflared` on the Mac opens an
outbound connection to Cloudflare, and Cloudflare sends the domain's requests down it. Your home
address stays hidden and the router needs no changes. Set `LOGDATE_EDGE=tunnel` in `logdate.env`
and Caddy is not started.

What it costs:

- **Uploads over 100 MB fail.** Cloudflare's free plan rejects larger request bodies before they
  reach the Mac. Journal sync sends each file in its own request, up to 100 MiB, so only files
  within a few megabytes of that limit fail. Android's cloud archive backups send the whole
  archive in one request and fail once an archive passes 100 MB. Downloads are not limited.
- **The server is down whenever the Mac or the home connection is.** That includes macOS updates
  that restart the Mac, and power cuts until it has started again.

### Set up the tunnel (Cloudflare dashboard)

1. Open Cloudflare Zero Trust, then **Networks → Tunnels → Create a tunnel**, choose
   **Cloudflared**, and name it `logdate`. Copy the token from the install command it shows (the
   long string after `--token`).
2. Under **Public hostnames**, add `cloud.logdate.app` with service type `HTTP` and URL
   `server:8080`. If Cloudflare says a DNS record already exists, delete the old
   `cloud` CNAME to `ghs.googlehosted.com` under **DNS → Records** first.
3. On the Mac, create `tunnel.env` in this directory containing `TUNNEL_TOKEN=<the token>`, and
   `chmod 600 tunnel.env`.

### Prepare the Mac

Run these once in Terminal. They keep the Mac awake, start it again after a power cut, and keep
its network up while the display sleeps:

```bash
sudo pmset -a sleep 0 disksleep 0 autorestart 1 womp 1 networkoversleep 1
sudo pmset -a powernap 0
```

Then install Docker Desktop and, in its settings, turn on **Start Docker Desktop when you sign
in**. Docker restarts the LogDate containers whenever it starts. Docker Desktop runs only while
you are signed in, so after a restart the server comes back once someone signs in. With FileVault
on, macOS cannot sign in by itself after a power cut; with FileVault off, turn on automatic
sign-in in **System Settings → Users & Groups** so it comes back unattended. That trade is yours
to make.

Give Docker at least 4 GB of memory under **Settings → Resources**.

### Start it

```bash
git clone https://github.com/TheHypertextStudio/logdate-mobile.git ~/logdate-mobile
cd ~/logdate-mobile/infra/selfhost
./generate-secrets.sh
sed -i '' 's/^LOGDATE_EDGE=caddy/LOGDATE_EDGE=tunnel/' logdate.env
./deploy.sh
curl https://cloud.logdate.app/api/v1/server/info
```

### Daily backups on the Mac

Save this as `~/Library/LaunchAgents/app.logdate.backup.plist`, replacing `YOU` with your
account name, then run `launchctl load ~/Library/LaunchAgents/app.logdate.backup.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>app.logdate.backup</string>
  <key>ProgramArguments</key>
  <array><string>/Users/YOU/logdate-mobile/infra/selfhost/backup.sh</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin</string></dict>
  <key>StartCalendarInterval</key>
  <dict><key>Hour</key><integer>3</integer><key>Minute</key><integer>15</integer></dict>
  <key>StandardErrorPath</key><string>/tmp/logdate-backup.log</string>
</dict>
</plist>
```

`backups/` sits on the same disk as the server, so also copy it somewhere else, such as
another drive with Time Machine.
