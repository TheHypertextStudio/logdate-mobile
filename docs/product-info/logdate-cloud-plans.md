# LogDate Cloud plans and prices

LogDate Cloud is the backup and sync service for LogDate, and it is how LogDate makes money. This
page records what it will cost at launch, why, and how the beta relates to it. The app's plan
screen, the server's plan catalog and the store products all follow this page.

## The two plans

|                             | LogDate Cloud                  | LogDate Cloud Plus                                             |
| --------------------------- | ------------------------------ | -------------------------------------------------------------- |
| Monthly price               | $3.99, from launch             | $7.99                                                          |
| Yearly price                | $39.99, from launch            | $79.99                                                         |
| Included storage            | 50 GB                          | 250 GB                                                         |
| Beyond the included storage | Not available; upgrade to Plus | $1 a month for each extra 50 GB, charged only while it is used |
| During the beta             | Free                           | $7.99 a month, as soon as it can be bought                     |

Both plans back up everything a person adds and keep it in step across their devices. Plus is for
people with a lot of photos and video: it includes five times the storage, and it never makes them
choose a bigger bundle in advance. They pay for extra space by the 50 GB while they use it and stop
paying when they stop using it.

Plus is not a launch-only plan. It is a paid plan from the day it can be bought, beta included; only
the base plan is free during the beta.

There is no free plan at launch. Everything in LogDate works on one device without an account;
LogDate Cloud is the paid part.

## The beta

During the beta, LogDate Cloud is free. Everyone who joins during the beta keeps the base plan
free after launch for as long as they keep their account. This is a promise the app makes on the
LogDate Cloud welcome screen, so it has to be kept: a beta account stays on the base plan at no
charge, and only moving to Plus costs it anything.

## Why these prices

The prices were set in November 2026 against what people already pay for the nearest things.

- Day One, the closest product, charges $49.99 a year for its Silver plan and $74.99 a year for
  Gold, and no longer offers monthly billing. LogDate Cloud is a little cheaper per year at both
  levels and can be paid monthly.
- Obsidian Sync charges $4 a month for 1 GB and $8 a month for 10 GB. LogDate's two prices match
  it and include many times the storage, because memories are mostly photos and video.
- iCloud+ and Google One sell bare storage for less: about $0.99 for 50 GB, $2.99 for 200 GB and
  $9.99 for 2 TB a month. LogDate Cloud is not competing as a drive, so it is not priced as one,
  but the extra-storage rate on Plus, $1 for 50 GB, is in line with what they charge for the same
  space.

Storing a gigabyte for a month costs LogDate about a cent and a half. The base plan's 50 GB costs
at most $0.75 against $3.99, and extra storage on Plus is sold at about a third above what it
costs, so both plans stay profitable when someone uses everything they are given.

Recheck the competitors' prices before launch; they move.

## What exists today, and what launch still needs

The app shows both plans and their launch prices on the LogDate Cloud plan screen
(`CloudAccountPlansContent`). It does not show how much storage each plan includes, because the
server does not give that yet; the only amount it names is the 50 GB step that extra space on Plus
is charged in.

- The server's plan catalog (`plans` table, read through `GET /api/v1/plans`) still holds its
  original rows: `free` at 1 GB, `standard` at 100 GB and `pro` with no limit. Launch needs them
  replaced with the two plans above, and beta accounts kept on a base plan that costs nothing.
- Neither plan has a store product. A plan's price is meant to come from the store, already in
  the person's currency; the prices written in the app are the launch prices from this page and
  give way to the store's once a plan is on sale.
- Paying for extra storage by use needs metered billing. The app stores cannot bill by use, so
  Plus has to be sold through Stripe.
- The app has no way to buy or change a plan yet.
