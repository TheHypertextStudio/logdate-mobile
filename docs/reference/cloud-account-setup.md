# Creating and signing in to a LogDate Cloud account

LogDate works without an account. A LogDate Cloud account adds backup and sync across devices.
Nothing in the account has a password: a person proves who they are with a **passkey**, a
cryptographic key pair that the device or its password manager holds, or with a Google account.
An email address is optional. This page describes how the app guides a person through creating an
account or signing in, what each step asks for, and how failures are reported. The server side of the
same flows is in [the auth API notes](../../server/docs/auth-v1-api.md), and the tokens that result are
described in [JWT authentication](../jwt-authentication.md).

The flow is implemented by
[`CloudAccountOnboardingViewModel`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/account/CloudAccountOnboardingViewModel.kt)
and the screens beside it in `client/feature/core`. The same flow is reached from onboarding and from
settings. It records the step it was entered at, so Back from that step leaves the flow instead of
walking into steps that were skipped.

## The steps

| Step                | What it does                                                                                                                                  |
| ------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `Welcome`           | Explains what LogDate Cloud adds and which server will be used, the hosted one or a custom one. It offers to create an account or to sign in. |
| `SignIn`            | Signs in with a passkey, optionally with a username, or with Google. This is the path for an account that already exists.                     |
| `DisplayName`       | Asks for the name shown on the account. It is skipped when onboarding already captured a name, and the name can still be edited at the end.   |
| `Username`          | Asks for a unique username and checks it as the person types.                                                                                 |
| `PasskeyCreation`   | Creates the account and its first passkey. The platform shows its own passkey prompt.                                                         |
| `EmailVerification` | Offers to verify an email address. It appears only when email verification is available for the server, and it can be skipped.                |
| `Complete`          | Runs the first sync and then lets the person into the app.                                                                                    |

Google sign-in is offered only in builds that have a Google OAuth client ID configured. Without one the
server would reject every token, so the option is hidden.

The username check waits 300 milliseconds after the last keystroke and starts at three characters, so
it does not send a request for every letter. The server enforces the same rules the screen shows:
3 to 50 letters, numbers and underscores, not a reserved name, and a display name of up to 100
characters.

## What creating an account does

Creating an account is two server calls with a platform step between them. The first
(`/auth/signup/passkey/begin`) checks the username and display name and returns the passkey options.
The platform then creates the passkey, and the second call (`/auth/signup/passkey/complete`) sends it
back and creates the account. A passkey is created before any data syncs, and later sign-ins use it.

The new account's ID is the installation's [canonical owner](./device-identity.md#the-canonical-owner),
the identifier the journal has used since the first entry was written. That is how entries written
before the account existed belong to it from the start.

The platform step differs per platform:

- **Android** uses the Credential Manager API.
- **iOS** uses the AuthenticationServices framework, through the passkey manager in
  `client/permissions`.
- **Desktop** has no passkey support in the app. The desktop build uses an account repository that
  reports account features as unavailable. It never invents credentials, because the server could not
  verify them.

The client also records the signed-in account through `PlatformAccountManager` in
`client/device`. On Android the account is added to the system `AccountManager`, with the account
type derived from the installed package. The iOS implementation takes a Keychain wrapper. The desktop
implementation is `DesktopAccountManager`.

## Signing in on a device that already holds entries

A device that has entries but no account can sign in to an existing account. Merging those entries into
the account is a decision the person must make, so the sign-in first fails with a "local data needs
adoption" result and the screen asks. Only after the person agrees does the app sign in again with
adoption turned on and attach the local entries to the account.

## After the account exists

The app first queues the entries already on the device for upload, because writes made before an
account existed were never queued. Doing it more than once for the same account is harmless. It then
runs the first sync while the screen shows progress. That sync settles as success, partial (it
finished but some uploads failed), timed out, or failed. Every outcome lets the person into the app.
A timed-out sync is retried by the periodic sync worker, and a partial or failed one is shown by the
sync status banner so it can be retried, so a poor connection never blocks the first launch.

## How failures are reported

Messages say what failed and what to do next. They never show the exception text, which contains host
names and status codes. Validation problems appear next to the field. Failures that come from the
network name the server the person chose: the hosted one, or "your server" for a custom one, and
distinguish a server that cannot be reached, one that does not respond and one that answers with
something the app cannot read. A custom server that is reachable but does not offer the account
protocol is refused before it is saved. Passkey failures separate a cancelled prompt, a device without
passkey support and a prompt that failed. A rate limit, a taken username and an unknown account each
have their own message. The mapping is in
[`CloudAccountFailureMessages.kt`](../../client/feature/core/src/commonMain/kotlin/app/logdate/feature/core/account/CloudAccountFailureMessages.kt).

## Security properties

- **No password is stored anywhere.** For a passkey account, the server holds only public keys.
- **The private key never leaves the device or its password manager.** The server holds the public key
  and sends a fresh random challenge for every sign-in.
- **Sign-in asks the device for user verification**, a fingerprint, a face or the screen lock, so that
  holding an unlocked device is not the same as holding the passkey.
- **Session tokens sit in the platform's secure storage.** See
  [JWT authentication](../jwt-authentication.md#client-side-storage).
- **A passkey does not decrypt the journal.** Content keys come from the recovery phrase, and the
  passkey only unlocks a wrapped copy of them. See [Encryption](../explanation/encryption.md).
