# Writing use cases

A use case is a small class in `client/domain` that performs one business operation, such as saving
a note, loading the notes for a day or merging two journals. It sits between the repositories that
hold data and the ViewModels that present it. ViewModels call use cases instead of combining
repository calls themselves, so the rules of an operation live in one place and every screen that
needs it behaves the same way.

## Shape

A use case takes its collaborators in the constructor and exposes its operation as
`operator fun invoke`, so a caller invokes the instance like a function:

```kotlin
class RemoveNoteUseCase(
    private val notesRepository: JournalNotesRepository,
) {
    suspend operator fun invoke(uid: Uuid) {
        notesRepository.removeById(uid)
    }
}
```

- **Constructor parameters are interfaces.** `client/domain` depends on `client/repository`, which
  holds the repository interfaces, and not on the modules that implement them, so a use case cannot
  name a concrete repository. See [Dependency injection](../../dependency-injection.md) for how the
  implementations are bound.
- **The public surface is `invoke`.** A use case is not a manager: it keeps no state of its own, and
  its result depends only on its inputs and what its repositories return. Give a distinct operation
  its own use case instead of adding a second named method to an existing one. Overloads of `invoke`
  with different parameters are fine.
- **Register use cases with `factory`.** They are stateless, so Koin creates one per request. A use
  case that must hold shared state across calls, as `RefreshStreakUseCase` does, is registered with
  `single` so every caller sees the same instance.

## One-shot and observable operations

An operation that does work once is a `suspend operator fun invoke`. An operation that follows data
over time returns a `Flow` from an ordinary (non-suspend) `invoke`:

```kotlin
class FetchTodayNotesUseCase(
    private val repository: JournalNotesRepository,
) {
    operator fun invoke(buffer: Duration = 4.hours): Flow<List<JournalNote>> {
        // compute the day's range, then
        return repository.observeNotesInRange(start, end)
    }
}
```

Do not make an `invoke` both `suspend` and `Flow`-returning. A caller cannot tell whether the call
finishes the work or starts an observation, and it has to launch a coroutine just to obtain a stream.
Collectors decide when observation starts and stops; the use case only describes the stream.

## Errors and cancellation

A use case that catches exceptions must rethrow `CancellationException`, as `FetchEntryUseCase`
does. Swallowing it turns a cancelled coroutine into an ordinary failure and breaks structured
concurrency for the ViewModel that launched it.

## Where they are registered

Most use cases are registered in `domainModule`. Location and quota use cases have their own domain
modules (`locationDomainModule`, `quotaDomainModule`), because their dependencies would otherwise
form a cycle with the main module. The app module includes them in dependency order.
