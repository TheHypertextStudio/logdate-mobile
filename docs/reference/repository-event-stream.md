# Repository event stream

Other AT Protocol apps learn what a person published from LogDate in two ways. They ask the server
for it, or a relay tells them. A relay is a service that follows many servers and passes what they
announce to every app that listens. This page describes what the LogDate server announces, which
parts of an account it shows to strangers, and exactly how it behaves, so you can build against it
or check that it keeps its promises.

The stream is off unless a deployment sets `ATPROTO_FIREHOSE_ENABLED` to `true`; see the
[environment variables reference](../../server/docs/environment-variables.md). To switch it on in
production, follow the [repository event stream runbook](../runbook/repository-event-stream.md). The
reasons for splitting each account into a public repository and private spaces are in
[Private storage](../explanation/private-storage.md).

## What is public

An account has one repository of records that anyone may read, and any number of private spaces that
no one but the owner can. Only four kinds of record are public:

| Collection                          | What it holds                                                |
| ----------------------------------- | ------------------------------------------------------------ |
| `app.logdate.actor.profile`         | The person's name, description and photo.                    |
| `app.logdate.crypto.keyDeclaration` | A public key a device publishes so others can share with it. |
| `site.standard.publication`         | A publication the person publishes stories under.            |
| `site.standard.document`            | A story the person chose to publish.                         |

Collections that other apps are allowed to write through OAuth are public as well, because an app
that wrote a record expects other apps to read it. None are open yet.

Everything else is private and is never shown or announced: the `studio.hypertext.logdate.*` records
that older sync used, the `app.logdate.space.*` spaces, every sealed record, and every other
`app.logdate.*` collection, including ones added later. A collection is public only by being named in
`PublicRepoPolicy`, so a new collection starts private.

## Which accounts are shown

The server shows an account's repository only when all of these hold:

- the account is active;
- it keeps its journal in its personal space, not in legacy sync, because a legacy account stores
  every entry, journal and draft as a record in the same repository;
- other apps can look up its DID, which means a `did:web`, or a `did:plc` that
  `ATPROTO_PLC_PUBLISH_ENABLED` has published to the PLC directory.

For any other account every method below answers as it did before the stream existed: it asks the
caller to sign in, and it never confirms that the account exists.

An account that relays already know and that is no longer in good standing is reported, not hidden.
`getRepoStatus` answers `active: false` with a `status` to anyone, with no sign-in, and a revision when
the repository still exists:

| Status        | When                                                                    |
| ------------- | ----------------------------------------------------------------------- |
| `deactivated` | The account is not active.                                              |
| `suspended`   | The server's operators suspended the account.                           |
| `takendown`   | The server's operators took the account down.                           |
| `deleted`     | The account was deleted after relays heard of it. There is no revision. |

`getRepo` and `getLatestCommit` answer such an account with the error the protocol names:
`RepoDeactivated`, `RepoSuspended` or `RepoTakendown`, and `RepoNotFound` for a deleted one. Records,
listings and blobs stop being served. Followers also hear an `#account` event with `active: false` and the
status. Suspending, taking down and restoring go through `AccountModerationService`, which stores the
decision in the `accounts.moderation` column and sends the event; restoring sends `active: true`.

Showing individual records is not enough to show a whole repository. A repository exports as a tree
that names the collection and key of every record it holds, so a tree with a single private record in
it would reveal that record's name. The server therefore exports and announces a repository only while
every record in its tree is in a public collection. If a private record gets in, the repository goes
quiet instead of leaking, and the server refuses to write journal records into the repository of an
account that keeps its journal in a personal space, so that cannot happen by accident.

The same rule hides the repository's head, because a revision is the time of a write. An account that
moved into its personal space keeps its old journal records in the tree for a while before they are
erased. Until then `getLatestCommit` and `listRepos` do not show it, and `getRepoStatus` answers
`active: true` without a revision. An account announced before it wrote anything also has no revision.

## Writing public records

With the stream on, an account in a personal space writes public records through
`com.atproto.repo.createRecord` and `putRecord`, and deletes them with `deleteRecord`. Each record is
checked against its lexicon before it is stored: LogDate's own schemas for the profile and the sharing
key, and the Standard.site schemas for publications and documents, which the server bundles under
`lexicons/site/standard/`. A record with a missing required field, a value of the wrong type, text or a
photo over a limit, or a malformed date is refused with `InvalidRequest`, naming each problem and the
path of the value, and nothing is stored or announced. Fields the schema does not describe are accepted,
so records written for a newer schema stay valid. A collection that was opened to apps and has no schema
here is accepted as it is. The profile's key is always `self`.

## Methods

None of these needs a sign-in. Each answers for a shown repository only.

| Method                             | What it returns                                                                                                                                                                                                                                        |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `com.atproto.sync.subscribeRepos`  | The event stream, over a WebSocket.                                                                                                                                                                                                                    |
| `com.atproto.sync.listRepos`       | The shown repositories, 500 per page by default and 1000 at most, ordered by DID. A page can hold fewer than the limit and still carry a cursor, because one call looks at a bounded number of accounts; keep following the cursor until it is absent. |
| `com.atproto.sync.getRepo`         | The repository as a CAR file. `since` limits it to newer blocks.                                                                                                                                                                                       |
| `com.atproto.sync.getLatestCommit` | The commit CID and revision.                                                                                                                                                                                                                           |
| `com.atproto.sync.getRepoStatus`   | Whether the repository is active, and its revision.                                                                                                                                                                                                    |
| `com.atproto.repo.getRecord`       | One record of a public collection.                                                                                                                                                                                                                     |
| `com.atproto.repo.listRecords`     | A page of a public collection. A private collection is empty.                                                                                                                                                                                          |
| `com.atproto.sync.getBlob`         | A blob that a public record refers to.                                                                                                                                                                                                                 |

A record in a private collection answers `RecordNotFound`, exactly as a record that does not exist
does. A blob that no public record refers to answers `BlobNotFound`, even if the account uploaded it.

## Events

Each frame on the WebSocket is a DAG-CBOR header followed by a DAG-CBOR body. Every event carries a
`seq`: the server numbers events 1, 2, 3 and so on, and a relay that remembers the last number it saw
can ask for everything after it.

| Event       | When the server sends it                                                                                                                                                                                                                                                                                                                                                                                                 |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `#commit`   | A shown repository changed. It lists the records that were created, updated or deleted, and carries the commit, the record tree and the new records as a CAR file, so a relay can check the change without asking for more.                                                                                                                                                                                              |
| `#sync`     | A relay must fetch the repository again, because a diff on top of a state it never saw would not verify. Sent when an account is first announced, when the commit before this one was never announced, and when a commit changes more than 200 records or its blocks exceed 2 MB. The event lexicon replaced its old `tooBig` flag with `#sync` and these limits, which is why a commit over them is announced this way. |
| `#identity` | An account got an identity, or its handle or signing key changed.                                                                                                                                                                                                                                                                                                                                                        |
| `#account`  | An account became active, or stopped being active with a status: `deactivated`, `suspended`, `takendown` or `deleted`.                                                                                                                                                                                                                                                                                                   |

A `#commit` names the revision it follows in `since` and the tree root that revision had in
`prevData`. The first commit of a repository has neither.

## Following the stream

Connect to `/xrpc/com.atproto.sync.subscribeRepos` and optionally pass `cursor`:

- With no cursor the stream starts at the present.
- With a cursor, the server first replays every event after that number, then keeps the connection
  open and sends new events as they happen.
- A cursor older than the oldest event the server still keeps gets an `#info` message named
  `OutdatedCursor`, followed by everything the server has. That includes a cursor behind the newest
  number when every event has been deleted. The server keeps events for seven days. It remembers for
  good which accounts it announced, so a takedown or deletion long after the announcement still reaches
  the relays that heard of the account.
- A cursor beyond the newest event gets a `FutureCursor` error frame and the connection closes.
- A follower that stops reading gets a `ConsumerTooSlow` error frame and the connection closes.
- The server serves 64 followers at a time and closes extra connections with `TRY_AGAIN_LATER`.

Events live in the database, so every server instance serves the same log. Each instance reads new
events once for all of its followers: it looks every half second for events another instance stored,
and at once for the ones it stored itself. A follower that is further behind reads the log on its own
until it catches up.

On Cloud Run the stream is served by its own service. The main service talks HTTP/2 all the way to
the container, because Cloud Run limits HTTP/1 requests to 32 MiB and recordings can be larger. Cloud
Run cannot pass a WebSocket to a service set up that way, so the deploy also runs the same image as a
second service, named after the main one with `-repo-events` on the end, over plain HTTP/1. A load
balancer in front of the server's hostname sends `/xrpc/com.atproto.sync.subscribeRepos` to that
service and every other path to the main one. Both read the same event log, so a follower sees the
same events either way. Cloud Run ends a request after an hour at most, so a follower is disconnected
after an hour and should reconnect with the last cursor it saw. The
[Terraform README](../../infra/terraform/README.md#load-balancer) explains how the load balancer is
set up.

The library that reads these frames is `RepoEventFrames` in `shared/atproto-repo`. It turns a frame
into a `RepoCommitEvent`, `RepoSyncEvent`, `RepoIdentityEvent`, `RepoAccountEvent`, `RepoInfoMessage`
or `RepoErrorMessage`, and `CarCodec.readCar` reads the blocks a commit carries.

## Asking relays to follow

A relay has to be told a server exists. With `ATPROTO_RELAY_HOSTS` set, the server sends each relay
`com.atproto.sync.requestCrawl` with its own hostname when it starts, and again when it announces an
account for the first time, at most once every ten minutes. A relay that cannot be reached at startup
is asked again after 10, 20, 40 and 80 seconds.

## Log codes

Production logs keep only approved messages. These are the codes the stream writes.

| Code                                          | Meaning                                                                                                       |
| --------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `SERVER_REPO_EVENT_NOT_RECORDED`              | Announcing a change failed. The write itself succeeded, and the next change announces the repository's state. |
| `SERVER_REPO_EVENT_COMMIT_UNREADABLE`         | A commit could not be compared with the one before it, so the server sent `#sync` instead.                    |
| `SERVER_REPO_EVENT_PRIVATE_RECORDS_HELD_BACK` | A repository holding a private record was not announced.                                                      |
| `SERVER_REPO_EVENT_FOLLOWER_FAILED`           | A follower's connection stopped after an error.                                                               |
| `SERVER_REPO_EVENT_READ_FAILED`               | Reading new events failed. Followers stay connected, and the server reads again half a second later.          |
| `SERVER_REPO_EVENT_PRUNE_FAILED`              | Deleting events older than seven days failed. The next hourly sweep retries it.                               |
| `SERVER_RELAY_CRAWL_REQUESTED`                | A relay accepted a crawl request.                                                                             |
| `SERVER_RELAY_CRAWL_REFUSED`                  | A relay answered the request with an error.                                                                   |
| `SERVER_RELAY_CRAWL_FAILED`                   | A relay could not be reached.                                                                                 |
