# Sharing and circles

## Vision

**LogDate is a home to document, reflect on, and share memories.** This design develops the sharing
part of that purpose, following the [product positioning](../product-info/product-positioning.md).
Sharing connects memories with the people who were part of a moment or would care about it.
It should feel like handing someone a photo from your trip or reading them a line you wrote.

The purpose is connection through memories. Public feeds, follower counts, and audience growth
do not define success. Journaling is supporting functionality, not the product's market position;
"social journal" must not replace the broader purpose.

Circles are how LogDate does that. A circle is a named group of people you choose, such as Family,
Roommates or Hiking Buddies. You share a moment with one or more circles, or with specific people,
and only they see it. Nothing in LogDate is public unless you deliberately publish a single piece.

This document defines how sharing works so that every feature built on it follows the same rules:
circles, encrypted sharing, finding people you know from the Atmosphere, conversations, and safety. The
broader direction is in
[LogDate on the AT Protocol](../superpowers/specs/2026-10-09-logdate-atproto-interoperability-design.md).

## Principles

**Small world first.** LogDate has no public feed, no follower counts, no public likes and no
algorithmic discovery. Everything shared goes to people the author chose.

**Private by default.** A journal, entry or day is private until its author shares it. Sharing one
thing never shares anything else.

**Pull in, never push.** LogDate uses a person's existing connections, such as people they follow
on Bluesky, to help them find people who are already here. It never posts, follows or announces
anything elsewhere on their behalf.

**Nothing feels like a feed.** Shared items arrive like letters, not like posts in a stream. There
are no counts of how many people saw or reacted to something.

**Social common sense.** The [People](./people.md) feature already holds this standard: the product
can be incomplete, but it can never feel socially careless. Sharing raises the stakes. Being added
to a circle, removed from one, or blocked must never embarrass anyone.

## The pieces

Sharing uses five ideas. Keeping them separate is what keeps the model simple.

| Idea | What it is | Who can see it |
|---|---|---|
| Person | Someone in your life, as LogDate understands them from your memories. Defined in [People](./people.md). | Only you |
| Connection | Two LogDate accounts that agreed they can share with each other. | Each person sees only their own connections |
| Circle | A named group of your connections, such as Family. | Only you |
| Group | A shared space around one thing, such as a wedding or a class trip, that people join by invite. | Its members |
| Share | One thing you shared, and who you shared it with. | You and the people you shared it with |

A **person** is not automatically a **connection**. People are private notes about the people in
your life, whether or not they use LogDate. Someone becomes reachable once they have a LogDate
account and you've connected. You can then link the two ("This is Sam"), so your memories of Sam
and your sharing with Sam line up. Only you see that link.

## Connections

LogDate doesn't have "friends" or "followers". A connection means one thing: the two of you agreed
that you can share with each other. It says nothing about how close you are or how much either of
you shares.

Connections are always mutual. Both people agree, and both can share. That is deliberate:

- **One-way access would build an audience.** If people could receive without being reachable in
  return, someone with many receivers would effectively have followers. Small world first means no
  audiences.
- **One-way access is the shape of surveillance.** A parent who receives everything from a teen,
  or a partner who insists on receive-only access, has power the other person can't see. Mutual
  connections keep both sides visible to each other.
- **One-way switches would force a lie or a rejection.** If someone silently stopped receiving,
  their contact would keep sharing into nothing and believe it arrived. Telling them would
  announce the rejection. Neither is acceptable.

**Nobody owes anything back.** Mutual means either of you *can* share, not that you should. A
grandparent can connect, enjoy every photo and never share a thing. LogDate never prompts anyone to
reciprocate. There's no "You haven't shared with Grandma in a while."

**Seeing less from someone is muting, not disconnecting.** If you mute a connection, their shares
still arrive, collapsed in Shared with you and without notifications. They aren't told, and they
aren't misled: what they shared did reach you, and you chose how to see it. Unmute any time.

A connection only grants permission. It shows nothing by itself: no profile, no activity, no list
of anyone's circles or other connections. You see something only when it's shared with you.

### Making a connection

Every connection starts with someone who wants you there:

- **An invite.** One person sends a private link or shows a QR code, and the other accepts.
  Invites are never posted anywhere.
- **People you know on the Atmosphere.** If you signed in with Bluesky, LogDate can suggest people
  you follow who are already on LogDate, mutual follows first. Choosing one sends them an invite.
  Nothing connects automatically.

There are no requests to receive someone's shares. Requests need a way to find strangers, they turn
saying no into a social event, and they are how follower culture starts. To hear from someone, they
invite you, or they share something with you directly.

An account is needed because shared items are end-to-end encrypted to the recipient's devices, so
LogDate has to know which devices are theirs. Sharing with someone who has no LogDate account, for
example through an encrypted link that opens in a browser, would be a separate feature closer to
publishing.

### Ending a connection

Removing a connection ends sharing both ways and removes what each of you shared with the other,
except copies someone already saved. Blocking does the same and also stops future invites and
suggestions. Neither person is notified.

## Groups

Some sharing isn't personal. The guests at a wedding, the families on a class trip, or the people
on a group vacation want to share with each other for a while, without connecting to everyone
involved one by one.

A group is a shared space for that:

- **People join by invite**, through a link or QR code from someone already in the group.
- **Members can share into the group**, and every member receives what's shared there. The person
  who started the group decides whether everyone can share or only they can.
- **Members can see who else is in the group.** This is the difference from circles: a group is a
  shared place, so its membership isn't secret.
- **Being in a group doesn't connect you to its members.** You can reach them only inside the
  group. Leaving the group ends that.
- **A group can end on a date**, for example a week after the wedding. After that nothing new can
  be shared, and its members keep what they saved.

Groups take care of most "in my network, but not personal" needs without one-way access.

## People and connections

[People](./people.md) and connections answer different questions, and the difference has to stay
sharp.

A **People record** is LogDate's private understanding of someone in your life, built from your own
memories: your writing, transcripts, photos and events. It exists for recall, meaning, search and
relevance. It can describe anyone, such as a child, a parent who has died, an ex, or a coworker
mentioned once, and that person never sees it, needs no account, and may never know LogDate exists.

A **connection** is a relationship two people agreed to, so they can share with each other.

Linking the two says "this account belongs to someone I already think of this way." These rules
govern the link:

1. **Nothing in your People record is ever visible to them.** Linking doesn't share your label for
   them, your notes, inferred groupings or confidence, and it doesn't tell them a record exists.
2. **Linking follows the People rules.** When someone connects, LogDate can suggest linking them to
   an existing person, as a suggestion in People review, never automatically. If you say they
   aren't the same, that answer sticks. A wrong link is exactly the "socially careless" failure the
   People feature exists to avoid.
3. **Most people never become connections, and that's normal.** A People record is not a lesser
   version of a connection, and the product never treats connecting as the "upgrade" for a person.
4. **The link points one way.** Your People record can point to a connection. Their account knows
   nothing about your record of them. Each of you has your own private understanding of the other.
5. **What the link enables is yours alone.** Things Sam shares with you can appear alongside your
   own memories on Sam's page, and the share sheet can suggest Sam when you write about Sam. None
   of this changes anything on Sam's side.

## Circles

A circle is a named group of your connections. Circles work like Google+ circles or
Instagram's Close Friends lists:

- **Circles are private to their owner.** Nobody can see your circles, their names, or who is in
  them. Someone in your "Coworkers" circle never learns the circle is called that.
- **Circles are one-directional.** Putting Sam in your Family circle says nothing about Sam's
  circles. Sam isn't told, and nothing changes for Sam until you share something.
- **Someone can be in several circles**, and a share can go to several circles at once. Someone in
  two of the chosen circles receives the item once.
- **Circles are for sharing, not for sorting your memories.** Organizing your own life is what
  People and journals are for.

The first time someone shares, LogDate suggests a few starter circles, such as Family and Close
Friends, without creating them until they're used. Empty circles that were never used aren't kept.

## What can be shared

| Item | What the recipient gets |
|---|---|
| Entry | That entry: its text, photos, video and audio, with transcripts |
| Day | The entries from that day you chose to include, plus the day's date and places, if you include them |
| Journal | The journal and its entries, including entries you add later, until you stop sharing it |
| Rewind | The Rewind story, as it was when shared |
| Postcard | The postcard |
| Sticker | The sticker |

Some things are never shareable, because they are about you or about other people rather than
moments you made:

- location history
- your notes about people
- health readings
- drafts
- anything LogDate inferred, such as suggested people or moments, unless it is part of a shared
  item's visible content

**Location is off by default.** Shared entries and days don't include where they happened unless
the author turns that on for that share. Home and work stay private even then.

## Sharing something

Share is available from every shareable item. It opens one sheet:

1. Pick circles and individual people. Recent choices come first.
2. See exactly what will be shared: which entries, whether location is included, and for a journal,
   that future entries will be shared too.
3. Send.

The author can always see who an item is shared with and change it later from the item itself.

**Who receives a share is fixed when it's sent.** Someone added to a circle later doesn't receive
things shared with that circle before they joined. Shared journals are the exception: a journal
shared with a circle reaches whoever is in the circle as new entries are added.

## Receiving

Shared items appear in **Shared with you**, grouped by the person or group they came from, newest first.
They don't mix with your own memories, and they never appear in a timeline of strangers.

Each new share sends one quiet notification: "Sam shared a day with you." There is no notification
for being added to a circle, for someone viewing something, or for reactions from anyone except
the person you're talking to.

**Muted** connections' shares still arrive here, collapsed and without notifications.

A recipient can save a shared item into their own LogDate, for example adding a shared photo to
their own day. The saved copy becomes theirs, and unsharing doesn't remove it. Whether the author
is told is an open question below.

## Responding

Responses follow how people actually respond to something shared with them: privately, to the
person who shared it.

- **Reactions** come from a small fixed set. The author sees who reacted with what. Other
  recipients don't.
- **Replies** go to the author only, as a private conversation about that item. Other recipients
  of the same item don't see them, and there's no group thread.
- There are no counts anywhere: no "12 reactions", no "seen by 5".

Private replies are the deliberate choice that keeps sharing from becoming a feed. Group threads on
shared items are out of scope for now.

## Unsharing and removal

- **Unsharing** an item removes it from every recipient's Shared with you, and they can no longer
  open it.
- **Removing someone from a circle** stops future shares to that circle from reaching them. Items
  already shared with them stay shared, unless the author also unshares them. The share sheet makes
  that choice explicit.
- **Removing a connection** removes everything you shared with them and everything they shared
  with you, for both of you, except copies already saved.
- **Leaving a group** stops you from receiving anything new from it. Things you saved stay yours.

The product says plainly what removal can't do. Someone who already opened a photo could have
saved a copy. The copy for this is: "They can't open it in LogDate anymore. Anything they already
saved stays with them."

## Blocking and safety

Blocking someone ends the connection completely, in both directions. You don't appear in their
suggestions, their invites don't reach you, nothing either of you shared remains visible to
the other, and they are not told they were blocked.

If you signed in with Bluesky, the people you block there, and the mute and moderation lists you
use there, are applied in LogDate too.

Reporting works without LogDate reading your content: the person reporting chooses what to include
in the report. Detailed safety design belongs to the Safety for small worlds project.

## Journals with audiences

The Figma journal designs already gave journals an audience. That becomes "sharing a journal": a
journal shared with a circle stays shared, and entries added later reach the circle too. The
journal's settings show who it's shared with and how to stop.

Journals that several people write in together are a separate feature and aren't part of this
model yet.

## Words

| Say | Don't say |
|---|---|
| Share with… | Post, publish (except for actual publishing) |
| Circle | Audience, group, list |
| Connected, People you share with | Friends, followers, following |
| Shared with you | Feed, inbox |
| Only you can see this | Private mode, hidden |

The interface never uses protocol words: no DID, PDS, handle (except when finding someone by
handle), repo, Lexicon or AT Protocol. Copy never speaks as "we", never guilts, and never
celebrates numbers.

## Pull in, never push, flow by flow

| Flow | What LogDate reads | What LogDate writes elsewhere |
|---|---|---|
| Signing in with Bluesky | Your Atmosphere identity | Nothing |
| Finding people you know | Who you follow, and mutual follows | Nothing |
| Safety | Your blocks, mutes and moderation lists | Nothing |
| Sharing with circles | Nothing | Nothing outside LogDate |
| Publishing a story | Nothing | The one piece you chose to publish |

## Not in this model yet

- Group threads on shared items
- Journals written together by several people
- Public profiles, follower lists or discovery
- Sharing with people who don't have a LogDate account, other than by publishing

## Open questions

- **Reaction set.** Which reactions are in the fixed set, and can they be stickers?
- **Starter circles.** Which starter circles to suggest, and how many.
- **Saving a shared item.** Should the author be told when someone saves it into their own
  LogDate, or would that feel watched?
- **Groups and responses.** Should replies inside a group stay private to the author, as everywhere
  else, or can a group have a shared conversation?
- **Muting and notifications.** Should muting also cover a connection's shares into groups you're
  both in?
