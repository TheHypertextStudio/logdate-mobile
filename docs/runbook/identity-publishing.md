# Identity publishing runbook

Keep LogDate identities private by default. Leave `ATPROTO_PLC_PUBLISH_ENABLED`
off in deployed environments. The legacy setting submits new identities to the
public directory during signup and the legacy command can submit existing
identities in bulk. Use the loopback rehearsal and read-only inventory below.
A person must review and confirm publication of their own identity before any
public directory write.

[Identity publishing](../explanation/identity-publishing.md) explains the
permanent public history and the safeguards required for publication.

## Rehearse publishing

Rehearse on the exact commit that production runs, on your own machine, before you touch production.
The rehearsal needs Java 17 or later, Node.js 22 or later with corepack, Python 3, curl and the
PostgreSQL server tools, and it only ever talks to addresses on your machine. It starts a throwaway
database, the reference PLC directory and a LogDate server, then follows this runbook with test
accounts, including a bad publish and its recovery.

1. Check out the commit that production runs, and run the rehearsal:

   ```bash
   ./run identity:rehearse
   ```

   Use `--accounts <n>` to change how many existing accounts it creates, and `--skip-server-build`
   to reuse the server build from an earlier run. The first run also downloads and builds the PLC
   directory, which takes a few minutes.

2. Expect it to print `Rehearsal passed` and exit with status 0. If it stops, the last `==>` line
   names the step that failed, and the logs and reports are in
   `tmp/identity-publishing-rehearsal/<run>/`.

## Confirm the safeguards

Publishing makes every account a public identity, so confirm each of these on production on the day
you start. If any one fails, stop and fix it first.

1. Confirm that private reads require sign-in. The repository read endpoints must refuse a request
   that carries no session, for an account that has entries. Each of these four requests must
   answer `401`:

   ```bash
   curl -i "https://cloud.logdate.app/xrpc/com.atproto.sync.getRepo?did=<your DID>"
   curl -i "https://cloud.logdate.app/xrpc/com.atproto.repo.listRecords?repo=<your handle>&collection=app.logdate.entry"
   curl -i "https://cloud.logdate.app/xrpc/com.atproto.repo.getRecord?repo=<your handle>&collection=app.logdate.entry&rkey=<a record key>"
   curl -i "https://cloud.logdate.app/xrpc/com.atproto.sync.getBlob?did=<your DID>&cid=<a blob CID>"
   ```

2. Confirm that signing keys and sessions have their own secrets. Account signing keys are
   encrypted with `ATPROTO_SIGNING_KEY_KEK` and hosted sessions are signed with
   `ATPROTO_SESSION_SECRET`, and the server refuses to start in production when either is missing
   or equal to `JWT_SECRET`. Check that both are bound to their own Secret Manager secrets at exact
   versions, and that the running revision started with them:

   ```bash
   grep -n 'ATPROTO_SIGNING_KEY_KEK\|ATPROTO_SESSION_SECRET\|JWT_SECRET' infra/terraform/production.tfvars
   gcloud run services describe <service> --region <region> --project <project> \
       --format='value(status.conditions[0].status)'
   ```

   Expect each of the two to name its own `secret_id`, a different one from `JWT_SECRET`'s, and
   expect the service condition to read `True`.

3. Confirm that the OAuth signing key persists. Note the `kid` that production serves, deploy or
   restart a revision, and expect the same `kid` afterwards:

   ```bash
   curl -s https://cloud.logdate.app/oauth/jwks | jq -r '.keys[].kid'
   ```

4. Confirm that handle resolution works. Check your own handle against the production server, and
   expect every line to be `[PASS]`, with only the DID document check allowed to fail while publishing
   is still off:

   ```bash
   ./run deploy:check-handle --handle <your handle> --service-url https://cloud.logdate.app --verify-did-document
   ```

   Then ask an outside resolver, and expect it to return your DID:

   ```bash
   curl "https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=<your handle>"
   ```

5. Confirm that people can hold their own recovery key. The server's descriptor must list
   `atprotoRecoveryKeysV1`, which means the server was started with `ATPROTO_RECOVERY_KEYS_ENABLED`
   set to `true` in the deployment contract's `env_vars`:

   ```bash
   curl -s https://cloud.logdate.app/api/v1/server/info | jq '.data.protocolFeatures'
   ```

   Then sign in to a test account with its passkey in the app. The app registers the account's
   recovery key by itself, without a second prompt; Account > Recovery phrase says whether it is set
   up. Check that the key appears first in the identity's rotation keys. If LogDate's key is listed
   first, LogDate can overrule the person, and the safeguard is not met.

6. Confirm that sign-in is passkey-only. Both of these requests must be refused on production, even
   for an account that once had a password:

   ```bash
   curl -i -X POST https://cloud.logdate.app/xrpc/com.atproto.server.createSession \
       -H 'content-type: application/json' -d '{"identifier":"<handle>","password":"x"}'
   curl -i -X POST https://cloud.logdate.app/xrpc/com.atproto.server.createAccount \
       -H 'content-type: application/json' -d '{"handle":"<new handle>","password":"x"}'
   ```

   Expect `400` with `{"error":"InvalidRequest","message":...}` from each, and expect no account
   to exist for the new handle afterwards.

7. Confirm that public repository commits follow the specification. Run the repository tests, which
   export repositories and verify them with the pinned reference `@atproto/repo` library, on the
   commit that production runs:

   ```bash
   ./gradlew :shared:atproto-repo:jvmTest
   ```

   Then download a test account's repository from production and verify it with
   `tools/atproto-repo-verify/verify.mjs` against the signing key in the account's DID document.

8. Confirm that PLC operations are valid. The rehearsal sends real operations to the reference
   directory, which refuses any with a bad signature or the wrong signing key, so a passing
   rehearsal on the deployed commit is the check. Run the server's identity tests as well:

   ```bash
   ./gradlew :server:test
   ```

## Inspect identity status

Render the contract for the deployed release and use the inventory command to
compare stored identities with the public directory. It reads existing state
without writing to the directory.

```bash
./scripts/render-cloud-run-contract.sh --environment production --release-sha <deployed release SHA> \
    > <contract file>
./run identity:publish-job status --contract-file <contract file>
```

Private identities normally appear as `unpublished`. The inventory can also
find missing genesis operations, mismatches, or identities controlled
elsewhere. Read the [state definitions](../reference/identity-publishing.md#states)
before taking action on any unexpected result.

## Respond to an unexpected directory write

Stop the specific publishing execution if one is running and confirm the
deployed bulk switch is off. Preserve its report and compare the directory
audit log with the locally stored operation chain. A request can succeed even
when its response was lost, so reconcile the observed state before planning
another write. Escalate an unintended publication to the affected account
owner. A public operation cannot be removed from directory history.
