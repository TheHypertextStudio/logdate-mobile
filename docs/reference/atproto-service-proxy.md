# AT Protocol service forwarding

The PDS forwards GET and POST XRPC requests for active accounts that have opted
to make their LogDate identity public. Account management, repository and sync
operations, private journal methods, and Bluesky preferences stay on the PDS.
An OAuth app needs the generic transition grant for non-chat services. OAuth
chat forwarding awaits a dedicated chat grant.

Clients can select a service using `atproto-proxy: did#service`. In its
absence, Bluesky AppView methods, chat methods, and moderation reports use the
[Bluesky service directory](https://docs.bsky.app/docs/advanced-guides/api-directory).
The PDS looks up the DID on each request, verifies the returned identity and
service identifier, and connects to the resulting HTTPS origin. It refuses
redirects and private network addresses. Looking up each target avoids routing
through stale service metadata at the cost of an extra network request.

The PDS signs a credential for the XRPC method with the account's public
identity signing key. The credential expires within 60 seconds and includes the
service identifier for explicit targets. For the three Bluesky service
references above, it uses the bare DID for compatibility with the current
[reference PDS](https://github.com/bluesky-social/atproto/blob/main/packages/pds/src/pipethrough.ts).
The PDS forwards the query, procedure body, content type, and label headers.
Session credentials, cookies, and the proxy-selection header stay at the PDS.
Clients receive a generic error message when an upstream service declines the
request.

Each instance caps active forwarding requests, enforces a per-account request
budget, and bounds DID documents, request bodies, and response bodies. These
limits favor predictable resource use over large or streaming payloads.
Read-after-write reconciliation is outside the current forwarding path.

## Bluesky preferences

`app.bsky.actor.getPreferences` reads the signed-in account's document from
LogDate. `app.bsky.actor.putPreferences` replaces the whole document. An
empty list clears the settings. PostgreSQL stores one document per account,
deleted with the account. Clients need an active public identity and an
OAuth client needs the generic transition grant. Preference requests stay
at LogDate even when a client sends a proxy header.

The document limit is 256 KiB. Settings must identify a Bluesky preference
type. LogDate preserves fields for unfamiliar Bluesky preferences so newer
clients can read back what they wrote. LogDate does not collect birth dates
through this API and discards the server-derived age preference if a client
echoes it back.
