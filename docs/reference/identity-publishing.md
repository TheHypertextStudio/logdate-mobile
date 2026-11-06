# Identity publishing reference

This page lists the exact behaviour of the identity publishing command: its arguments, the states it
sorts identities into, its exit codes, the log codes it writes, the Cloud Run job that wraps it, and
the local rehearsal. For why publishing works this way, see
[Identity publishing](../explanation/identity-publishing.md). For the steps an operator follows, see
the [identity publishing runbook](../runbook/identity-publishing.md).

The write commands in this reference describe the legacy bulk publisher.
Use them only in the loopback rehearsal. Keep deployed identities private
until each person authorizes publication of their own identity.

## Settings

Publishing is controlled by one server setting and one URL, both described in full in the
[environment variables reference](../../server/docs/environment-variables.md).

- `ATPROTO_PLC_PUBLISH_ENABLED` is `false` unless a deployment sets it to `true`. The deployment
  contract renderer accepts `true` only for production.
- `ATPROTO_PLC_DIRECTORY_URL` names the PLC directory and defaults to `https://plc.directory`.
  Outside production it also accepts plain `http` on a loopback address, which is how the rehearsal
  points the server at a directory on the same machine.

## Command

The server image contains a one-shot command that compares identities with the PLC directory and
publishes the ones the directory does not have yet. It never starts the HTTP server. It has three
forms:

```text
server --identity-publishing status  [--handle HANDLE] [--limit N] [--report-file PATH]
server --identity-publishing publish --dry-run [--reconcile] [--handle HANDLE] [--limit N]
                                     [--batch-size N] [--report-file PATH]
server --identity-publishing publish --confirm-directory URL [--reconcile] [--handle HANDLE]
                                     [--limit N] [--batch-size N] [--operations-per-minute N]
                                     [--max-failures N] [--report-file PATH]
```

`status` reads every account's identity, compares it with the directory and changes nothing.
`publish --dry-run` does the same and lists what a real run would do. `publish` sends identities to
the directory, and it refuses to start unless `ATPROTO_PLC_PUBLISH_ENABLED` is `true` and
`--confirm-directory` names exactly the directory the server is configured for.

The options are:

- `--handle HANDLE` limits the run to the account with that handle. Without it, every account is
  visited.
- `--limit N` limits the run to the oldest `N` accounts. Accounts are always visited oldest first.
- `--batch-size N` sets how many accounts go between progress lines. The default is 25.
- `--operations-per-minute N` sets the most operations sent to the directory per minute. The default
  is 30, and reads are not limited.
- `--max-failures N` stops the run once `N` accounts have failed. The default is 1, so one
  systematic problem cannot repeat across every account.
- `--reconcile` also sends a corrected operation for each identity whose directory copy differs from
  what LogDate serves.
- `--report-file PATH` writes a JSON report with one entry per account, naming its ID, its DID, its
  state before and after, the action taken and any failure. Only the file's owner can read it.

Every run is safe to repeat. An identity the directory already has is compared and never sent again,
so an interrupted run can start again and continue where it stopped.

## States

The command sorts every account's identity into one of the following states, and the summary lines
in the logs use the same names.

- `published` means the directory has the identity and it matches what LogDate serves. `publish`
  does nothing for it.
- `published_unrecorded` means the directory has the identity and it matches, but LogDate has not
  stored the directory's CID for it, for example because an earlier run stopped between sending and
  recording. `publish` records the CID and sends nothing.
- `unpublished` means LogDate has a stored genesis operation that the directory has never seen.
  `publish` sends it.
- `not_provisioned` means the account has no identity yet. `publish` creates one, which sends it.
- `mismatch` means the directory's copy has a different handle, signing key, PDS address or recovery
  key. The report lists which one. `publish` changes nothing unless `--reconcile` is given.
- `controlled_elsewhere` means LogDate's key is no longer a rotation key of the identity, because its
  owner took control of it. `publish` never touches it.
- `not_plc` means the account uses `did:web`, which LogDate serves itself. `publish` does nothing
  for it.
- `missing_genesis` means the account has a `did:plc` but no stored genesis operation derives it, so
  there is nothing to send. An operator has to investigate.
- `incomplete` means the account has a DID but no handle or no signing key. An operator has to
  investigate.
- `tombstoned` means the directory reports that the identity was deactivated. An operator has to
  investigate.
- `directory_error` means the directory could not be read. A later run can try again.

## Exit codes

The command reports its outcome as one of four exit codes.

- `0` means every identity the run looked at is published and matches.
- `1` means a publish failed, the run stopped at its failure limit, an identity needs an operator,
  or no account has the handle that `--handle` named.
- `2` means the command refused to run, because of bad arguments, publishing being switched off, an
  unconfirmed directory, or a server that does not give accounts `did:plc` identities.
- `3` means nothing failed, but some identities still need publishing or reconciling.

## Log codes

Production logs keep only approved codes and counts, never account IDs, handles or DIDs. The
publishing work writes these codes.

- `SERVER_PLC_OPERATION_PUBLISHED` means the directory accepted an operation, whether it came from
  sign-up, a key change or the command.
- `SERVER_PLC_OPERATION_REJECTED` means the directory refused an operation as invalid.
- `SERVER_PLC_DIRECTORY_RATE_LIMITED` means the directory asked LogDate to slow down.
- `SERVER_PLC_DIRECTORY_UNREACHABLE` means sending failed before the directory answered.
- `SERVER_PLC_OPERATION_UNCONFIRMED` means the directory accepted an operation but its audit log did
  not show it straight away.
- `SERVER_IDENTITY_PUBLISHING_STARTED`, `SERVER_IDENTITY_PUBLISHING_COMPLETE` and
  `SERVER_IDENTITY_PUBLISHING_STOPPED` mean that a run started, finished, or stopped at its failure
  limit.
- `SERVER_IDENTITY_PUBLISH_FAILED` means one account failed during a run.
- `SERVER_IDENTITY_PUBLISHING_REFUSED_USAGE`, `SERVER_IDENTITY_PUBLISHING_REFUSED_PUBLISHING_OFF`,
  `SERVER_IDENTITY_PUBLISHING_REFUSED_DIRECTORY_UNCONFIRMED` and
  `SERVER_IDENTITY_PUBLISHING_REFUSED_NOT_PLC` mean that the command refused to run, and the suffix
  says why.
- `SERVER_IDENTITY_PUBLISHING_FAILED` means the command itself failed, for example because the
  database was unreachable.

Each run also writes summary lines that carry only counts:

```text
SERVER_IDENTITY_PUBLISHING_SUMMARY accounts=25 unpublished=25 publish_genesis=25 now_published=25 exit_code=0
```

The names in a summary line are the states above counted before the run, the actions taken
(`publish_genesis`, `provision_and_publish`, `record_cid` and `reconcile`, or the same names
prefixed with `would_` in a dry run), the states afterwards prefixed with `now_`, and `failed`.

## Cloud Run job

`./run identity:publish-job` runs the command as a one-shot Cloud Run job. The job takes its image,
environment, secret versions and service account from a rendered deployment contract, as the account
blob cleanup job does, so it reads the same database and directory as the deployed server. Automatic
retries are off. When the execution finishes, the script prints the job's `SERVER_IDENTITY_*` and
`SERVER_PLC_*` log lines.

```bash
./run identity:publish-job {status|dry-run|publish} --contract-file <contract> \
    [--confirm-directory <url>] [--reconcile] [--handle <handle>] [--limit <n>] \
    [--batch-size <n>] [--operations-per-minute <n>] [--max-failures <n>] [--timeout-seconds <n>]
```

The script refuses `publish` unless the contract sets `ATPROTO_PLC_PUBLISH_ENABLED` to `true` and
`--confirm-directory` matches the contract's directory. The `--timeout-seconds` option raises the
time the job may run, which matters for runs of more than a few thousand accounts at the default
pace.

## Rehearsal

`./run identity:rehearse` runs the whole publishing procedure on one machine, against test accounts,
without contacting the public directory. It accepts `--accounts N`, which sets how many existing
accounts to create (3 by default, at most 99), and `--skip-server-build`, which reuses the server
build from an earlier run.

It starts three things on loopback addresses: a throwaway PostgreSQL cluster, the reference PLC
directory built from a pinned commit of `bluesky-social/did-method-plc`, and a LogDate server built
from the checkout. It refuses any directory address that is not on the machine, and it starts the
server with only its own settings, so `ATPROTO_*` variables in the calling shell cannot redirect it.

The rehearsal needs Java 17 or later, Node.js 22 or later with corepack, Python 3, curl and the
PostgreSQL server tools (`brew install postgresql@18` on macOS). Its first run downloads and builds
the PLC directory into `~/.cache/logdate/did-method-plc/`. Logs, reports and data for every run stay
under `tmp/identity-publishing-rehearsal/`.

It stops at the first step whose result is wrong. The steps are:

1. With publishing off, it signs test accounts up through the real passkey sign-up and checks that
   the directory does not know them, that `status` reports them `unpublished` and that `publish` is
   refused.
2. It runs a dry run and checks that nothing reached the directory.
3. It switches publishing on, signs one more account up, and checks that the new identity resolves at
   once. It also checks that a run naming `https://plc.directory` is refused.
4. It publishes one account chosen by handle, checks it, and then publishes the rest in batches.
5. It checks that every identity resolves in the directory with the right handle and PDS address,
   that LogDate resolves each handle to the same DID, and that publishing again sends nothing.
6. It changes the server's PDS address, as if the identities had gone out with a wrong one, checks
   that `status` reports every identity as `mismatch`, and recovers with `publish --reconcile`.
7. It switches publishing off again and checks that `publish` is refused while `status` still works.
