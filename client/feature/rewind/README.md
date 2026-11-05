# Rewind Feature Documentation

The Rewind feature provides users with a beautiful, engaging way to view and interact with their weekly life summaries through a vertically scrollable list of floating cards.

Rewind supports **reflection** within LogDate's purpose: **a home to document, reflect on, and
share memories**. Describe it through the experiences and connections someone can rediscover,
with the interface serving that purpose. Follow the
[product positioning](../../../docs/product-info/product-positioning.md).

## 🎯 UX Design Philosophy

### Core Principles

**Always Show Content**: The rewind screen never displays an empty state. Even when no rewinds are ready, users see a placeholder card for the current week, ensuring the floating card design is always showcased.

**Instagram Story-like Navigation**: Vertical scrolling with snap-to-center behavior creates an immersive, focused viewing experience where each card gets full attention.

**Progressive Disclosure**: Visual affordances guide users through the content:
- Next card indicator shows when more content is available
- Cards fade and scale based on distance from center
- Hidden surprise message rewards users who scroll to the end

**Emotional Connection**: Small moments of delight (like the end-of-list surprise) create positive emotional associations with reviewing past memories.

### Interaction Model

- **Snap Scrolling**: Cards automatically center when scrolling stops
- **State-Aware Interactions**: Only available rewinds respond to taps
- **Visual Hierarchy**: Ready cards are vibrant and bold, pending cards are muted
- **Accessibility**: Maintains proper touch targets and screen reader compatibility

## 🏗️ Architecture Overview

### Component Hierarchy

```
RewindScreenContent
├── Scaffold (with TopAppBar)
└── FloatingRewindCardList
    ├── LazyColumn (with snap behavior)
    │   ├── FloatingRewindCard (multiple)
    │   └── EndOfListSurprise
    └── NextCardIndicator
```

### Key Components

#### `RewindScreenContent`
- **Purpose**: Main entry point and state management
- **Responsibilities**: Combines current/past rewinds, handles different UI states
- **UX Impact**: Ensures consistent experience across Loading/NotReady/Ready states

#### `FloatingRewindCardList`
- **Purpose**: Implements the core scrolling experience
- **Responsibilities**: Snap behavior, visual depth, progressive disclosure
- **UX Impact**: Creates the signature floating card experience

#### `FloatingRewindCard`
- **Purpose**: Individual card with dynamic visual effects
- **Responsibilities**: Scale/fade animations, state-aware styling, content display
- **UX Impact**: Provides focus and hierarchy through scroll-based animations

#### `NextCardIndicator`
- **Purpose**: Affordance for discovering more content
- **Responsibilities**: Show/hide based on scroll position, clear directional cue
- **UX Impact**: Solves "mystery meat navigation" in snap-scrolling interfaces

#### `EndOfListSurprise`
- **Purpose**: Delightful completion moment
- **Responsibilities**: Hidden reveal when user reaches end of content
- **UX Impact**: Rewards engagement with positive reinforcement

## 📱 Responsive Design

### Layout Constraints

- **Card Width**: Fills screen width with 24dp minimum edge padding
- **Maximum Width**: 360dp for optimal readability on large screens  
- **Card Height**: Fills available view height with maximum 9:16 aspect ratio constraint
- **Spacing**: 32dp between cards for comfortable scrolling

### Visual Effects

- **Scale Animation**: Cards shrink by up to 20% when away from center
- **Alpha Animation**: Cards fade by up to 60% when not focused
- **Elevation**: Dynamic shadow depth (0-12dp) based on focus
- **Z-Index**: Focused card appears above others in visual stack

## 🎨 Visual States

### Card States

#### Available Rewind
- **Surface**: Material 3 `primaryContainer` color for visual prominence
- **Typography**: Bold title, primary color label
- **Interaction**: Fully clickable with ripple effects
- **Indicator**: No status indicator needed

#### Pending Rewind
- **Surface**: Material 3 `surfaceVariant` color (muted)
- **Typography**: Medium weight title, outline color label
- **Interaction**: Non-interactive (taps ignored)
- **Indicator**: Small dot next to label

### Loading States

- **Current Week Placeholder**: "Coming Soon" title with muted styling
- **Loading State**: "Loading..." title with neutral messaging
- **Past Rewinds**: Standard available styling once loaded

## 🚀 Performance Considerations

### Optimization Strategies

- **Lazy Loading**: Only visible cards are composed and measured
- **Snap Behavior**: Uses Compose's optimized `rememberSnapFlingBehavior`
- **Animation Calculations**: Derived state minimizes recomposition
- **Memory Management**: Fixed card height prevents layout thrashing

### Accessibility

- **Touch Targets**: All interactive elements meet 48dp minimum
- **Screen Readers**: Proper content descriptions and navigation
- **Keyboard Navigation**: Focus management works with external keyboards
- **High Contrast**: Uses semantic colors that adapt to accessibility settings

## 🔄 State Management

### UI States

```kotlin
sealed interface RewindOverviewScreenUiState {
    data object Loading              // Shows placeholder card
    data class NotReady(...)         // Shows current week placeholder + past rewinds
    data class Ready(...)            // Shows current rewind + past rewinds
}
```

### State Transitions

1. **Loading → NotReady**: When past rewinds load but current week isn't ready
2. **Loading → Ready**: When both current and past rewinds are available
3. **NotReady → Ready**: When current week rewind processing completes

## 🎯 Future Enhancements

### Planned Features

- **Rich Media Blocks**: Photo thumbnails and location chips in cards
- **Gesture Navigation**: Swipe gestures for faster card navigation
- **Customizable Themes**: Seasonal or mood-based card styling
- **Sharing Integration**: Direct sharing from individual cards

### Technical Debt

- **Date Calculation**: Currently uses hardcoded dates, needs actual week calculation
- **Callback Generification**: RewindOpenCallback could be more generic
- **Block Content**: GridItem implementation needs completion

## 📖 Usage Examples

### Basic Implementation

```kotlin
@Composable
fun RewindScreen() {
    val viewModel: RewindOverviewViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    
    RewindScreenContent(
        state = uiState,
        onOpenRewind = { rewindId ->
            // Navigate to detailed view
            navController.navigate("rewind/$rewindId")
        }
    )
}
```

### Custom Styling

```kotlin
RewindScreenContent(
    state = uiState,
    onOpenRewind = onOpenRewind,
    modifier = Modifier
        .fillMaxSize()
        .background(customBackgroundColor)
)
```

## 🧪 Testing Considerations

### Key Test Scenarios

- **Empty Data**: Verify placeholder card appears
- **Single Card**: Ensure proper centering and no indicator
- **Multiple Cards**: Test snap behavior and indicator visibility
- **End of List**: Verify surprise message appears correctly
- **State Transitions**: Test loading → ready transitions

### Accessibility Testing

- **Screen Reader**: Verify proper content announcements
- **Keyboard Navigation**: Test focus management and card selection
- **High Contrast**: Verify color combinations meet accessibility standards

## 📚 Related Documentation

- [Material 3 Adaptive Design Guidelines](https://m3.material.io/foundations/adaptive-design)
- [Compose Lazy Lists Best Practices](https://developer.android.com/jetpack/compose/lists)
- [LogDate UI System Documentation](../ui/README.md)
- [Rewind Domain Logic](../../domain/README.md)
