# AT Protocol OAuth test provider

This JVM fixture supplies the simulated Atmosphere used by the OAuth client and server integration
tests. It is a test dependency only, and is neither published nor included in the app or server.

`MockAtmosphere` verifies client assertions and DPoP proofs with Nimbus, independently of the client
signer. It enforces PKCE, one-use authorization codes, rotating refresh tokens, client metadata,
issuer and subject bindings, and revocation. Its request log supports assertions that a sign-in
does not write repository records. `TestClock` makes expiry deterministic.

All accounts, tokens and endpoints are fixtures. The HTTP client uses an in-memory engine and does
not contact those endpoints. This proves protocol behavior under the modeled conditions; live
Bluesky acceptance remains a separate check.
