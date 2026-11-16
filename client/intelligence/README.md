# `:client:intelligence`

**Derived features built from a person's entries: summaries, people, moments, events and Rewind**

## Overview

This module reads what a person has written, photographed and recorded and derives something from
it: a one-line summary of an entry, the people an entry mentions, the moments and events inside a
day, and the story and curated media of a week or a year (Rewind). Some of that work asks a cloud
language model. The module is built around one fact: a cloud model is sometimes unreachable, not
allowed or not configured, and the app has to behave well anyway. Every call therefore returns a
typed result instead of throwing, and a result that was computed once is cached so it keeps
working offline.

The module returns derived data to its callers. It does not create journal entries.

## Components

| Area                  | Types                                                                                                             |
| --------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Results               | `AIResult`, `AIError`, `AIUnavailableReason`                                                                      |
| Model client          | `GenerativeAIChatClient`, `OpenAiClient`, `NoOpGenerativeAIChatClient`                                            |
| Extractors            | `EntrySummarizer`, `PeopleExtractor`, `MomentExtractor`, `EventNamingExtractor`                                   |
| Narrative             | `WeekNarrativeSynthesizer`, `YearNarrativeSynthesizer`, `RewindSequencer`, `AnnualRewindSequencer`                |
| Rewind generation     | `RewindStrategySelector` choosing `FullLLMRewindStrategy`, `QuotesOnlyLLMRewindStrategy` or `LocalRewindStrategy` |
| Rewind media curation | `RewindMediaCurator` and its filter, scoring, bucketing and diversity stages                                      |
| Milestones            | `MilestoneDetector`, `LocationChangeMilestoneDetector`                                                            |
| Weather               | `HistoricalWeatherProvider`, `OpenMeteoHistoricalWeatherProvider`                                                 |
| Cache                 | `GenerativeAICache`, `OfflineGenerativeAICache`, `AICacheLocalDataSource`                                         |
| Structured output     | `StructuredAIExtractor`, `JsonStructuredOutputParser`                                                             |

Koin wires them in `intelligenceModule`, which includes `clientsModule`, a platform `cacheModule`
and a platform `curationModule`.

## Results instead of exceptions

Every public call returns an `AIResult<T>`:

- `Success(value, fromCache)`: the value, and whether it came from the cache.
- `Unavailable(reason)`: the call was not made. The reasons are `NoNetwork`, `DataSaverActive`,
  `ProviderDisabled`, `MissingCredentials` and `InputTooLarge`. This is an expected state, not a
  failure, and callers show a quiet fallback or nothing at all. The code produces `NoNetwork`,
  `DataSaverActive`, `MissingCredentials` and `InputTooLarge`.
- `Error(error, throwable)`: the call was made and failed. `AIError` is `Network`, `Timeout`,
  `RateLimited`, `ProviderUnavailable`, `InvalidResponse`, `UnsupportedModel`, `Unauthorized` or
  `Unknown`. The error type is the source of truth; UI code maps it to its own wording. The code
  produces `Unauthorized`, `RateLimited`, `ProviderUnavailable`, `InvalidResponse` and `Unknown`;
  transport failures and timeouts arrive as `Unknown`.

```kotlin
val summary = when (val result = summarizer.summarize(summaryId = "entry-123", text = text)) {
    is AIResult.Success -> result.value
    is AIResult.Unavailable -> null
    is AIResult.Error -> null
}
```

Before it calls a model, each extractor works through the same checks in this order, and the first
that applies decides the result:

1. **Input size.** Text longer than `MAX_AI_INPUT_CHARACTERS` (100,000) is `Unavailable(InputTooLarge)`.
2. **Cache.** A valid cached result is returned as `Success(fromCache = true)` unless the caller
   passed `useCached = false`. This is why a result that was computed once still works offline.
3. **Network.** No connection is `Unavailable(NoNetwork)`.
4. **Data usage.** A data usage mode that disallows AI calls is `Unavailable(DataSaverActive)`.
5. **Credentials.** With no API key the injected client is `NoOpGenerativeAIChatClient`, which
   answers `Unavailable(MissingCredentials)`.

Rewind chooses its strategy before any call. `RewindAIAvailability` reports a tier, and the tier
comes from the person's entitlement (`EntitlementRewindAIAvailability` in `:client:domain`), so a
signed-in person with a key can still get the local Rewind. `FULL` runs the model-written narrative
and falls back to a `LocalRewindStrategy` when the call comes back unavailable. `QUOTES_ONLY`
currently produces the local Rewind and labels it with the quotes-only tier; it makes no model call.
`NONE` uses local heuristics alone. Rewind is always produced.

## The model client

`GenerativeAIChatClient` takes a `GenerativeAIRequest` (messages, optional model, temperature and
response format) and returns an `AIResult<GenerativeAIResponse>`. `OpenAiClient` is the only
implementation. It calls the OpenAI chat completions API directly from the device, uses model
`gpt-4.1-mini-2025-04-14` and temperature 0.7 unless the request says otherwise, and maps failures:
HTTP 401 and 403 to `Unauthorized`, 429 to `RateLimited`, 5xx to `ProviderUnavailable`, any other
status or exception to `Unknown`, and an empty reply or an unusable JSON schema to
`InvalidResponse`.

**The API key** is read at runtime from the `OPENAI_API_KEY` environment variable or JVM system
property of the running process on Android and desktop. The iOS loader returns nothing. No build
step copies a key into a packaged app. `local.properties` is not read for this key at runtime. A
process without the key logs a warning at startup and gets the no-op client. See
[Encryption](../../docs/explanation/encryption.md) for exactly what each feature sends to the
model.

## Structured output

Features that need data rather than prose ask the model for JSON that matches a schema, using the
`json_schema` response format. `StructuredAIExtractor` is the shared base: it runs the size,
cache, network and data usage checks, submits the prompt with the schema, parses the reply,
writes the cache and translates every failure into an `AIResult`. A subclass supplies the system
prompt, schema, parser and version markers. `MomentExtractor` and `EventNamingExtractor` use it.

`JsonStructuredOutputParser` decodes a reply and reports `Success`, `Empty` or `Invalid`. With
`allowEmbeddedJson` it also accepts a reply that wraps the JSON object in other text, which the
week narrative uses. A reply that does not parse becomes `Error(InvalidResponse)`, and
`StructuredAIExtractor` does not cache it.

## The cache

`OfflineGenerativeAICache` has two layers: an in-memory LRU of up to 100 entries or 1 MiB, and a
persistent store of up to 500 entries or 10 MiB. Writes go to both. Reads check memory first and
promote persistent hits into memory.

**Keys.** A key hashes the content type (`summary`, `people`, `narrative`, `onboarding`,
`moments` or `events`), the provider, the model, the prompt version, the schema version, the
template id and the input text with whitespace trimmed and collapsed. Changing any of those, for
example the model or a prompt version, produces a different key, so stale results are never served
for new settings; they age out instead. Each entry also stores those fields, a hash of the input and
an expiry time, and a read rejects and deletes an entry whose expiry has passed or whose stored
fields do not match the request.

**Lifetime.** The time to live is set per feature: 30 days for summaries, people and the week
narrative, 90 days for the year narrative and 7 days for structured extractors by default. Every
write also removes expired entries, and if the persistent store is over its limits it evicts the
oldest entries first.

**Failure handling.** Cache errors are logged and never fail the request. A file that cannot be
decoded is deleted. `purge()` clears both layers.

**Storage.** The persistent layer writes one JSON file per entry, named after the sanitized key,
in an `ai_cache` directory under the app cache directory on Android, under the Caches directory on
iOS, and in `logdate_ai_cache` under the system temp directory on desktop. The codec has hooks for
compression and encryption, and both are currently no-ops, so entries are stored as plain JSON. The
cached text is derived from the person's entries, so treat the directory as private. Android and iOS may clear their cache directories at any
time, which only costs a recomputation.

## Platform differences

The API key loader and the two platform Koin modules (`cacheModule` and `curationModule`) are
`expect` declarations in common code with an implementation per platform (Android, iOS, JVM). The
cache data source and the Rewind media signal extractor are interfaces that each platform
implements. Everything else is common code.

## Testing

```bash
./gradlew :client:intelligence:jvmTest
```

Tests use `FakeGenerativeAICache` and `FakeGenerativeAIChatClient` from the `fakes` package, so no
test touches the network. They cover the extractors, the cache and its key strategy, the OpenAI
request and response mapping, structured output parsing, the narrative synthesizers, Rewind
strategies and curation, and a set of realistic journal texts for people extraction.
