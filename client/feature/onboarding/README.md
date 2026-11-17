# `:client:feature:onboarding`

**User onboarding and first-run experience**

## Overview

Provides a comprehensive onboarding experience for new users, guiding them through setup, feature introduction, and initial content creation. This module handles the critical first-time user experience and returning user re-engagement.

## First launch

A new person sees three things, in this order, and nothing else stands between them and Home.

1. **The opening** (`OnboardingOpeningScreen.kt`). A few days of someone's life lie across the
   whole screen: photos, notes, a voice note, a drawing, and today's date on today's pile. They
   come in from every side, the date lands last, and from then on each piece can be picked up,
   moved and thrown. When the person asks how LogDate works, today's pile lines up on one page,
   laid out the way LogDate shows a day. Both states are one screen and one set of pieces. See
   [The opening scene](#the-opening-scene).
2. **The first memory.** "Add your first memory" opens the same editor the rest of the app uses,
   on its empty page, which offers every way to start one in a row: write, handwrite, capture,
   record, or choose a photo. A first memory is not started as text for the person, because the
   opening has just shown that a memory can be written, spoken or pictured. The editor has no
   wording or controls of its own for first launch.
3. **Home.** Saving that entry is what finishes onboarding. The app records that onboarding is
   complete and opens Home with the entry on it. If that record cannot be saved, the completion
   screen opens instead, says so and offers a retry, because opening Home with onboarding
   unfinished would send the next launch back to the start.

Someone who already has an account chooses "I already have an account" on the opening and goes
through sign-in and `WelcomeBackScreen.kt` to Home.

Cloud backup is not offered during first launch. It is set up later in Settings.

### The opening scene

The scene is not a picture with a size of its own. `StepScaffold` tells it where the step's words
and actions are on this screen, and `arrangeOpening` (`ui/opening/OpeningArrangement.kt`) fills
the room around them: today's pile between the words and the actions, a few more pieces hanging
off the top and bottom edges, and, where the screen is wider than the step, more down each side.
A larger screen gets more pieces and larger ones, never more empty margin, but never so many
that the pile stops being what the eye lands on: a phone shows seven and a tablet about fifteen.
`StepScaffold` also says how far down the system draws its status bar over the scene. A photo
still hangs off the top under the clock, and anything with words on it starts below the bar.

The pieces are an example, listed in `OpeningPieces.kt`: today's photo, words and voice note
with today's date on them, and other days' memories around the edges. There is one date on the
screen and it is the real one, read from `LocalToday`. Times on the pieces are written by the
same formatter as the rest of the app.

When the table is tidied, `fileToday` (`ui/opening/OpeningDay.kt`) lines today's pile up on one
page. The page reads like a day in the app: "Today" and the date at its head, then the photo,
the words and the voice note. There is one page on a screen of any size; it changes shape to
show the photo as large as the room allows. A piece lying where the page will be gets out of its
way, and the rest of the table stays as it is.

Both functions are plain geometry with no Compose state, so `OpeningArrangementTest` holds them
to these rules on a phone, a small phone, an unfolded phone and a tablet either way up: how many
pieces, how much of the screen they reach, and that nothing ever lies over the words or the
actions.

`OpeningScene.kt` draws the arrangement and `OpeningTable.kt` moves it. Each piece is a
`PaperPiece` whose position is read while it is drawn, so the whole table can move at once
without the scene being recomposed. A piece that is let go keeps the speed it was thrown with
and stays where it stops, except that it never rests over the words or the actions, or mostly
off the screen. A piece still in flight cannot be picked up, so it never takes a tap meant for a
button it is crossing. Turning the device moves the nearer pieces further, through
`rememberDeviceParallax` in `:client:ui`. With reduced motion on, nothing moves by itself and
the pieces are simply where they belong.

The photos are free to use without credit. Their sources are recorded here so they can be traced:

| File                            | Photo                                    | By                                                      | Licence       |
| ------------------------------- | ---------------------------------------- | ------------------------------------------------------- | ------------- |
| `onboarding_scene_concert.jpg`  | Crowd People                             | Anthony Delanoix, StockSnap                             | CC0           |
| `onboarding_scene_pizza.jpg`    | Homemade Pizza                           | Burst, StockSnap                                        | CC0           |
| `onboarding_scene_hike.jpg`     | Backbone Trail                           | Santa Monica Mountains National Recreation Area, Flickr | Public domain |
| `onboarding_scene_record.jpg`   | Record Player Limited Edition Pink Vinyl | John K Thorne, Flickr                                   | CC0           |
| `onboarding_scene_beach.jpg`    | Junction                                 | romainguy, Flickr                                       | CC0           |
| `onboarding_scene_roadtrip.jpg` | The Hills Have Vanes                     | cogdogblog, Flickr                                      | CC0           |
| `onboarding_scene_rain.jpg`     | Rain bokeh                               | freestocks.org, Flickr                                  | CC0           |

### One look

First launch, signing in and LogDate Cloud are where LogDate introduces itself, so they share one
look that the rest of the app does not use. `FirstLaunchTheme` (`:client:theme`) sets it: warm
cream paper, plum for what you tap, peach for what is being offered, bold headings, and the
expressive button shapes and motion. Every onboarding route is registered through
`firstLaunchEntry` in `OnboardingRoute.kt`, and the account flow wraps itself, so no step falls
back to the device's colors.

Pictures are made of one object, `PaperPiece` (`:client:ui`): a flat piece of paper lit from
overhead. The opening scene and the LogDate Cloud picture are both built from it. Nothing is
tilted in three dimensions: a tilted card skews its text and needs drawn-on thickness and gloss
to explain the angle, which reads as plastic.

Buttons are `StepPrimaryButton` and `StepSecondaryButton` (`:client:ui`). A step never builds its
own.

### LogDate Cloud

LogDate Cloud is the backup and sync service, and it is a subscription. Its welcome step
(`CloudAccountWelcomeContent`, in `:client:feature:core`) has one job: show what it does, say
what it costs, and offer the two ways in.

- The picture shows the same day on two devices.
- The peach card is the offer (`CloudOffer`). During the beta it says LogDate Cloud is free and
  stays free for people who join now. Once subscriptions start it shows the store's price for the
  plan; the plan comes from the server's plan catalog and the price from the store, never from a
  string in the app.
- "See plans" on that card opens the plans step (`CloudAccountPlansContent`). Each plan is read
  in the order someone decides by: its name, three short lines on what it gives, and last and
  quietest its price. The prices and the reasoning behind them are in
  `docs/product-info/logdate-cloud-plans.md`.
- The welcome asks one thing: make an account, or use the one you have. It does not ask where
  the account is hosted.
- Signing in takes the name a person knows (`CloudAccountSignInContent`, `parseSignInName`). A
  bare username is an account on LogDate Cloud. A name that says where its account lives,
  `alex@journal.example.com` or a full handle such as `alex.example.com`, points the app at that
  server before the passkey is asked for. Nobody picks a server to sign in.
- Hosting matters only while making an account, and only to someone who runs their own LogDate
  server. The username step names the host in one quiet line, "Hosted by LogDate Cloud", because
  the host is part of the name being chosen. That line opens the hosting step
  (`CloudAccountHostingContent`): LogDate Cloud, or your own server and its address. The words
  there, "your own server" and "server address", are the ones the people who use it already have.

Each step shows only what its one decision needs. Anything further appears when the person asks
for it.

The words on these steps are short and plain: a title that says what the step is for, one line
under it, and buttons named for what they do. They do not describe who makes LogDate or how the
service works.

### Where the title and the actions go

The title and the main action are in the same place on every onboarding step, so a person tapping
through never has to find them again. `StepScaffold` in `:client:ui` is what guarantees it, and
every step, the opening included, is laid out by it:

- The title starts at the same height on every step. Above it are the back row and a slot for a
  small mark (`StepHeroIcon`); both keep their room on a step that has neither.
- A picture goes in the `illustration` slot, under the title and its one line, so it never pushes
  the title down. It is given the height the step has left once everything else has its room: it
  fills a tall step and shrinks in a short one. A picture should ask for the most it wants and
  draw itself to fit less.
- A step that is about a picture more than a form passes a `scene` instead. It is drawn across
  the whole window, in front of the step, and is told where the words and the actions are so it
  can fill the room around them. The opening is the one step that does. The scene is laid out
  in the same pass as the step (`StepScene.kt`), so it is there in the first frame, and a touch
  that lands on nothing in the scene reaches the step underneath, so the actions stay usable.
- On a tablet, an unfolded foldable or a desktop window, the whole step sits in one panel in the
  middle of the window, laid out exactly as on a phone. A step is one decision, so it is not
  stretched across a large window or split into two panes. Two panes are used only where a hinge
  or a phone held sideways leaves no room to stack.
- The actions are pinned at the bottom, the same distance from the edge and the same width.
- The main action comes first, in an area that always has room for a second action under it. A
  step with only one action leaves that room empty instead of dropping the button lower.
- A footer, such as legal links or a hint, sits above the actions, so having one does not push
  them up.

Only what is between the title and the actions changes from step to step. A new step should pass
its words, picture and buttons to `StepScaffold` and never place them itself;
`OnboardingOpeningLayoutTest` and `StepScaffoldLayoutTest` fail if the title or the main action
moves.

## Architecture

```
Onboarding Feature
├── Onboarding Flow
├── Welcome Screens
├── Feature Introduction
├── First Entry Creation
└── Settings Configuration
```

## Key Components

### Core Components

- `OnboardingRoute.kt` - Navigation graph for onboarding
- `OnboardingViewModel.kt` - State management for onboarding process
- `OnboardingUiState.kt` - UI state representation
- `OnboardingFeatureModule.kt` - Dependency injection

### Onboarding Screens

- `OnboardingStartScreen.kt` - Initial welcome screen
- `OnboardingOverviewScreen.kt` - App feature overview
- `MemorySelectionScreen.kt` - Data import interface
- `EntryCreationScreen.kt` - First journal entry creation
- `OnboardingNotificationScreen.kt` - Notification setup
- `OnboardingCompletionScreen.kt` - Completion celebration

### Returning User Experience

- `WelcomeBackScreen.kt` - Returning user re-engagement
- `WelcomeBackViewModel.kt` - Returning user state management

### Cloud & Backup

- `CloudAccountSetupScreen.kt` - Cloud account creation
- `BackupSyncScreen.kt` - Backup and sync configuration

## Features

### User Onboarding

- **Guided Setup**: Step-by-step introduction to the application
- **Feature Overview**: Introduction to key app capabilities
- **First Entry Creation**: Guided creation of first journal entry
- **Interactive Tutorials**: Learn-by-doing feature education
- **Notification Setup**: Configuration of reminders and alerts

### Cloud Integration

- **Account Creation**: Seamless account creation flow
- **Cloud Sync Setup**: Configuration of sync preferences
- **Backup Configuration**: Data backup explanation and setup
- **Privacy Controls**: Clear privacy information and controls
- **Memory Import**: Importing memories from other services

### Returning User Experience

- **Welcome Back Flow**: Special experience for returning users
- **Account Recovery**: Account and data recovery options
- **Data Migration**: Migration from older app versions
- **Feature Updates**: Introduction to new features

### Data Capture

- **Audio Recording**: Voice entry capabilities
- **Photo Capture**: Image capture for first entries
- **Text Entry**: Rich text input for journal entries
- **Templates**: Starter templates for first entries

## Dependencies

### Core Dependencies

- `:client:domain` - Business logic
- `:client:ui` - Shared UI components
- `:client:repository` - Data access
- `:client:media` - Media handling
- `:client:feature:core` - Core app features
- `:client:billing` - Subscription management
- `:client:intelligence` - AI features
- **Compose Multiplatform**: UI framework
- **Material 3**: Design components

## Usage Patterns

### Onboarding Navigation Graph

```kotlin
fun NavGraphBuilder.onboardingGraph(
    onNavigateBack: () -> Unit,
    onWelcomeBack: () -> Unit,
    onOnboardingComplete: () -> Unit,
    onGoToItem: (route: OnboardingBaseRoute) -> Unit,
) {
    navigation<OnboardingRoute>(
        startDestination = OnboardingStart,
    ) {
        composable<OnboardingStart> {
            OnboardingStartScreen(
                onNext = { onGoToItem(AppOverview) },
                onStartFromBackup = { onGoToItem(OnboardingComplete) },
            )
        }
        // Additional screens in sequence
    }
}
```

### Feature Introduction

```kotlin
@Composable
fun OnboardingOverviewScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    // Feature overview content
    Column {
        // Feature explanations
        Button(onClick = onNext) {
            Text("Continue")
        }
    }
}
```

## Dependency Injection

```kotlin
val onboardingFeatureModule = module {
    viewModel { OnboardingViewModel(get(), get()) }
    viewModel { WelcomeBackViewModel(get()) }
    viewModel { MemorySelectionViewModel(get()) }
}
```

## TODOs

### Core Features

- [ ] Add interactive feature tutorials
- [ ] Implement onboarding analytics
- [ ] Add personalization questionnaire
- [ ] Improve account creation flow
- [ ] Add multi-platform onboarding consistency

### User Experience

- [ ] Implement progress tracking
- [ ] Add skip options for power users
- [ ] Improve animation transitions
- [ ] Add onboarding completion badges
- [ ] Implement onboarding state persistence

### Content Creation

- [ ] Add more first entry templates
- [ ] Improve media capture experience
- [ ] Add guided journaling prompts
- [ ] Implement voice-to-text for first entries
- [ ] Add starter journal suggestions

### Integration

- [ ] Improve integration with notification system
- [ ] Add third-party service import options
- [ ] Implement better cloud setup guidance
- [ ] Add onboarding for premium features
- [ ] Improve data migration tools
