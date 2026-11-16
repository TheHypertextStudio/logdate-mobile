# People

## Availability

People ships turned off. It is offered as an opt-in switch in Labs on Android, and Labs is the only
place it is turned on or off. While it is off, Settings shows no People row or category and the
People pages close as soon as they open. The directory already built stays stored on the device and
is shown again when People is turned back on.

Names are found on the device in text entries and voice note transcripts, and every name found is a
suggestion: it appears for review after at least two mentions, and it joins the directory when the
person confirms it. A person can be removed, and a removed or rejected name is not suggested again.
Contacts can be imported on Android. The sections below describe the intended product, which goes
further than this: people in search, sync and export of the directory, choosing individual contacts
to import, and names in non-Latin scripts are part of the design and are not built.

## Vision

People should give LogDate a durable understanding of who matters in the user's life.

Most memory products are good at storing moments and weak at understanding them. They can show
what was written, what was photographed, or what happened on a specific day, but they have little
understanding of the relationships that give those moments meaning. That is the gap People is meant
to close.

A memory is rarely important because of the raw event alone. It is important because it happened
with a partner, a child, a parent, a close friend, or a recurring group of people who define a
chapter of life. Once the product can recognize those people with reasonable confidence, it becomes
much better at connecting memories, understanding patterns, surfacing the right things later, and
helping the user navigate their life in a way that feels human rather than mechanical.

People should not feel like contact management. It should feel like the product gradually learning
the social structure of a life.

## The product bet

The bet behind People is that identity is one of the highest-leverage primitives in a memory
product.

Search gets better when a name is more than a keyword. Memory grouping gets better when repeated
appearances by the same person are understood as part of a larger pattern. Relevance gets better
when the product knows that one person shows up in moments that matter and another shows up only
incidentally. Over time, this becomes one of the clearest ways for LogDate to evolve from a store
of memories into a system that can help interpret them.

That only works if the feature is both automatic and restrained.

If People depends on the user manually assembling and maintaining a directory, it will never become
central enough to matter. The product needs to discover likely people on its own. But if it turns
every weak signal into a stable identity too early, the entire experience gets noisy and brittle.
The directory becomes a cleanup problem. The user stops trusting the feature. The product starts
acting more certain than it has earned the right to be.

So the core product decision is straightforward: People should discover broadly and confirm
conservatively.

## Product model

People should always operate with two layers.

The first layer is the set of people the product feels good about. These are stable identities that
belong in the main People directory and can be treated as part of the app's durable understanding
of the user's life.

The second layer is the set of people the product has probably found but is not ready to present as
fully confirmed. These identities should stay separate until confidence improves or the user
resolves them.

In the current build, only the person's confirmation moves a name from the second layer to the
first. Repeated mentions alone do not, because the name finder is not accurate enough to be trusted
with that decision.

This distinction is the feature, not an implementation detail.

If the product collapses the uncertain layer into the stable one, it loses the very thing that
makes the feature feel intelligent. The directory starts filling with weak guesses. Similar names
get merged too early. A casual mention inside a transcript starts carrying more meaning than it
should. In other words, the system starts treating possibility as truth.

The better model is to let the product be ambitious in the background and disciplined in what it
surfaces. That gives LogDate room to learn aggressively without forcing the user to live inside the
product's uncertainty.

## What this should do for the user

People should create value in three ways that are immediately legible and one that compounds over
time.

### Recall

The most direct value is recall. A user should be able to move from a person to the memories and
events connected to them without needing to remember a date, a title, or a specific phrase they
wrote months ago. If they want to revisit time with a sibling, a partner, or a close friend, that
should feel like a natural path through the product.

### Meaning

The second value is meaning. Once the app understands that the same person appears across notes,
transcripts, events, and recurring moments, those artifacts stop looking isolated. They start to
read as part of a relationship, a routine, or a phase of life. That is where People becomes more
than tagging. It starts helping the product understand what a set of memories is actually about.

### Search

The third value is search. Names should stop functioning as loose text and start functioning as
things the product understands. A search for a person should not return a pile of unrelated keyword
matches. It should open a path into the moments that matter for that person.

### Long-term intelligence

The compounding value is that People gives LogDate a better sense of what is meaningful. Once the
product has a more reliable understanding of who matters, it can make stronger decisions about
grouping, resurfacing, and relevance. This is one of the clearest ways for the product to become
more personally aware over time.

## Trust and confidence

People only works if it behaves with social common sense.

The feature can tolerate incompleteness. It cannot tolerate feeling socially careless.

That means the product needs a high bar for what it treats as stable identity. It can notice weak
patterns in the background, but it should not force them into the foreground. When the product is
uncertain, it should look uncertain. It should suggest, separate, or ask. It should not bluff.

The main failure mode here is not "the metadata is a little messy." The real failure is that the
product seems wrong about the people in the user's life. If it keeps returning the wrong person,
merges two different people because the evidence is merely close enough, or repeatedly resurrects a
guess the user already rejected, then the product stops feeling smart and starts feeling socially
tone-deaf.

For that reason, correction has to matter. Confirmation should make the system stronger. Rejection
should meaningfully reduce the chance of the same mistake coming back. Over time, the feature
should feel like it learns from the user's life, not like it keeps trying the same bad idea in
slightly different forms.

## The role of contacts

Contacts should make People better, but they should not define what People is.

Full contacts access is the highest-automation path. If the user grants it, the product should get
faster and cleaner. Recognition should happen sooner. Duplicate identities should be less common.
Names and photos should improve where that information exists. For users who want the least
friction, this should clearly be the best setup path.

Selected contacts access matters just as much because it shapes the privacy story of the feature.
It should not feel like a compromised or apologetic option. It should feel like a deliberate choice
for users who want more control while still getting real value. The promise is simple: if the user
shares the people they care about most, the product should become materially better at recognizing
and organizing memories around those people.

People also needs to remain useful without contacts at all. The feature cannot depend on the
address book, both because many users will not grant access and because contacts do not tell the
whole story anyway. The product still has the user's own writing, transcripts, event context,
photos, and repetition over time. Without contacts, it should move more carefully and rely more on
review, but it should still be able to build meaningful value.

The principle is simple: contacts are an accelerator, not the foundation. The foundation is the
user's own memories.

## Product experience

The feature should feel simple at the surface, even if the system underneath it is doing difficult
work.

### People home

The main People screen should explain why the feature exists, offer one clear next step, and make
it obvious how to get either to the stable People directory or to unresolved suggestions. It should
not read like a technical control panel. Permission jargon, raw counts, and internal state labels
do not belong in the main experience.

### Directory

The directory is the stable layer of the feature. It should feel easy to scan and easy to trust. A
user should be able to open it and feel that the product basically understands their world. If the
directory feels noisy, then the feature is failing at the most important level.

### Review

Review is where uncertainty belongs. It is not an edge flow. It is what allows the product to
discover aggressively without contaminating the stable layer. If the app is unsure, that ambiguity
should be visible there instead of bleeding into the main People list.

### Person detail

Person detail should answer a clear question: why is this person part of the story LogDate is
capturing about this life? The answer should come from connected memories and events. That is what
turns People from a naming layer into a navigation layer.

### Search

Search should reflect the same model. A person's name should not just be text that happens to
appear in notes. It should be something the product understands well enough to organize around.

## Quality standard

People is good when it becomes useful without becoming reckless.

That means the setup is easy to understand, the privacy choices are clear, the main surface feels
calm, the directory feels stable, and the review flow catches ambiguity before it turns into mess.
It also means the feature already changes how the user moves through the product. They should be
able to navigate memories through people and feel that this is a better, more natural way to use
LogDate.

People does not need every possible recognition method or editing tool before it becomes worth
having. But it does need to feel coherent, trustworthy, and emotionally accurate. That is the line
that matters.
