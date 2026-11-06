# Identity publishing

Every LogDate account has an AT Protocol identity, made of a handle such as `alice.logdate.app` and
a permanent identifier called a DID. Identity publishing is the step that sends those identities to
the public PLC directory, where every other AT Protocol app can look them up. This page explains
what an identity is made of, why publishing one cannot be undone, which safeguards LogDate wants in
place before it publishes, and how the publishing command sorts identities so that it can run safely
more than once. The [identity publishing runbook](../runbook/identity-publishing.md) lists the steps
an operator follows, and the [identity publishing reference](../reference/identity-publishing.md)
lists the command's options, states, exit codes and log codes.

## Identities and the PLC directory

Suppose Alice signs up for LogDate. The server gives her the handle `alice.logdate.app` and a DID
such as `did:plc:ewvi7nxzyoun6zhxrhs64oiz`. A `did:plc` identity is defined by a chain of signed
operations. The first one, the genesis operation, names her handle, the address of the server that
hosts her data (her PDS), the key that signs her data, and the rotation keys that may sign later
operations. The DID is a hash of that genesis operation, so it cannot be changed without becoming a
different identity.

LogDate signs each account's genesis operation the first time the account needs an identity,
usually at sign-up, and stores it in its own hosted operation log. Another app, such as Bluesky,
cannot read that log. It asks the public directory at `https://plc.directory`, and until Alice's
operations are in the directory nobody outside LogDate can resolve her identity or use her account
anywhere else.

## Publication consent

A LogDate identity starts private. Publication requires the account owner's
explicit, informed confirmation for that identity. Keep the legacy
`ATPROTO_PLC_PUBLISH_ENABLED` switch off in deployed environments. The
switch submits new identities during signup, and the legacy command can
publish existing identities in bulk. Use the loopback rehearsal and
read-only inventory to inspect the system while per-account publication is
being built.

## Why publishing cannot be undone

The PLC directory only ever adds to an identity's history, and nothing can delete an identity or an
operation from it. A rotation key that is listed earlier than the key that signed an operation can
replace that operation within 72 hours of the directory accepting it, and after that the operation
is permanent. A tombstone operation deactivates an identity for good: the DID stays in the directory,
marked as deactivated, and can never be used again.

A mistake in what gets published therefore stays visible to the whole network. If Alice's identity
went out with the wrong PDS address, the best repair is a corrected operation on top of it. If an
identity went out for an account that should never have had one, such as a test account, nothing
removes it. This is why LogDate lets only production publish, why the command asks the operator to
type the directory it is writing to, and why a run stops at its first failure by default.

## Safeguards before publishing

Publishing makes the consenting account's identity visible to other services.
Those services can resolve its handle and verify the operations that control
it. The safeguards below apply before per-account publication goes live.

Private data has to stay private. Publishing invites other services to read an account's public
repository, so the endpoints that return repositories, records and blobs must still refuse a request
without a session. LogDate's journal entries are stored in that repository, and a missing check would
turn an account's identity into a way to read its journal.

Signing keys and sessions need secrets of their own. Each account's signing key is what proves its
operations and commits, and it is encrypted with a key-encryption key that is separate from the secret
that signs ordinary sessions. If those secrets were the same, one leaked secret would let someone sign
sessions and decrypt every account's key, and the server therefore refuses to start in production
unless they differ.

The OAuth signing key has to survive restarts. Other AT Protocol apps fetch the key that verifies
LogDate's access tokens, and a key that changed at every restart would make every token those apps
hold fail at once.

Handle resolution has to work as well. Another app trusts a handle only when the domain points at the
DID and the DID points back at the domain, so once an identity is public, a handle that does not
resolve shows as unverified in every other app. The
[handle resolution explanation](handle-resolution.md) describes both halves.

People need to be able to hold their own recovery key. Without one, LogDate's key is the only
rotation key, and whoever controls LogDate's servers controls every identity. A recovery key that
only the person holds is listed ahead of LogDate's key, so it can overrule LogDate within the
directory's 72-hour window. The key is derived from the person's recovery phrase, and registering,
replacing or using it needs a fresh passkey confirmation.

Nobody has to open or save the recovery phrase for this. The app already holds the phrase, so it
registers the key by itself. A passkey sign-in or sign-up hands the app a step-up grant, which is
the server's proof that the person confirmed with a passkey a moment ago. The grant lasts five
minutes, works once, and is accepted only for registering the first recovery key, so the person
never sees a second prompt. Replacing a key or using it always asks for the passkey again. When no
grant is available, the Account screen says that one passkey confirmation is still needed.

Sign-in has to be passkey-only. An identity that other apps can reach must not be protectable by a
password that someone could guess or reuse, so the server's `createSession` and `createAccount`
endpoints refuse passwords, even for an account that once had one.

The public repository has to follow the specification. Relays and other services read an account's
repository commits and reject any that deviate from the format, so a repository that works inside
LogDate but fails the reference implementation would be broken for everyone else. LogDate checks its
repository exports with the reference `@atproto/repo` library in its tests, and an operator repeats
that check against a real account before publishing.

The PLC operations themselves have to be valid. The directory refuses an operation whose signature is
not the low-S form, which is the single canonical signature the protocol allows, or that is not
signed by a rotation key of the previous operation. LogDate rejects such operations before storing
them, computes each operation's CID locally in the same way the directory does, and makes the
device check what a recovery key is about to sign. The rehearsal sends real operations to the
reference directory, so a passing rehearsal on the deployed commit shows that LogDate's operations
are ones the directory accepts.

## How the command stays safe to repeat

The publishing command compares each account with the directory before it does anything, and it
sorts the account into a state. An account whose identity the directory already has is only compared,
so running the command twice sends nothing the second time. An account whose genesis operation was
sent but whose CID was never recorded, because an earlier run stopped in between, is recorded
without sending again. An account whose directory copy differs from what LogDate serves is reported
as a mismatch and left alone unless the operator asks for a reconcile, because correcting it
publishes a new operation that cannot be removed. An account whose owner took control of the
identity with their own rotation key is never touched, because overriding that choice would defeat
the point of the recovery key.

Several accounts need a person rather than a command: an account with a DID but no stored genesis
operation, an account with no handle, and an identity the directory reports as deactivated. The
command lists them and does nothing for them, so the operator decides what each one means before the
run continues.

The command writes only counts and approved codes to production logs, because production logs
carry nothing that identifies an account. When an operator needs to know which accounts failed, the
command's report file names them, and only its owner can read that file.

## Rehearsal

Because publishing is irreversible, LogDate rehearses it on one machine first. The rehearsal starts
a throwaway database, the reference PLC directory (the software that runs `plc.directory`), and a
LogDate server built from the checkout, all on loopback addresses. It then follows the runbook's
procedure with test accounts, including a deliberate bad publish and its recovery, and it refuses to
talk to any address that is not on the machine. What the rehearsal cannot show is the state of
production itself, which is why the runbook has separate steps for checking production before
anything is sent.
