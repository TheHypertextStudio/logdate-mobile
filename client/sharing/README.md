# `:client:sharing`

**Content sharing and social media integration**

## Overview

Provides cross-platform content sharing capabilities with integration for social media platforms, system share sheets, and link generation. This module enables users to share journals, photos, and videos from the application to various external platforms.

This module supports **sharing** within LogDate's purpose: **a home to document, reflect on, and
share memories**. The value is letting people share chosen memories with chosen people; external
platform integrations are delivery mechanisms. Follow the
[product positioning](../../docs/product-info/product-positioning.md), and distinguish this module's
available export workflows from the broader [sharing design](../../docs/feature-design/social-model.md).

## Architecture

```
Sharing Module
├── Sharing Interface
├── Asset Generation
├── Platform Integration
└── Social Media Connectors
```

## Key Components

### Core Components

- `SharingLauncher.kt` - Main sharing interface
- `ShareAssetInterface.kt` - Content asset generation
- `ShareTheme.kt` - Theming for shared content
- `SharingModule.kt` - Dependency injection

### Platform Implementations

- `AndroidSharingLauncher.kt` - Android implementation
- `IosSharingLauncher.kt` - iOS implementation
- `DesktopSharingLauncher.kt` - Desktop implementation

### Asset Generation

- `AndroidShareAssetGenerator.kt` - Android-specific asset generation
- `ShareSheet.kt` - System share sheet integration

## Features

### Content Sharing

- **Journal Sharing**: Share journal entries and collections
- **Media Sharing**: Share photos and videos
- **Link Generation**: Create shareable links to content
- **Theme Support**: Themed content for sharing
- **Image Generation**: Dynamic image creation for sharing

### Social Media Integration

- **Instagram Stories**: Direct integration with Instagram Stories
- **Instagram Feed**: Share to Instagram feed
- **System Share**: Integration with system share sheet
- **Asset Preparation**: Proper formatting for each platform
- **URI Management**: Cross-platform URI handling

### Asset Generation

- **Background Layers**: Dynamic background generation
- **Sticker Layers**: Transparent overlay generation
- **Journal Covers**: Visual representation of journals
- **Theme Application**: Light/dark theme support
- **Content Formatting**: Platform-specific formatting

## Dependencies

### Core Dependencies

- `:client:media` - Media access and management
- `:client:repository` - Data access
- `:shared:model` - Shared data models
- **Koin**: Dependency injection
- **Napier**: Logging

## Usage Patterns

### Journal Sharing

```kotlin
class JournalDetailViewModel(
    private val sharingLauncher: SharingLauncher,
    private val journalRepository: JournalRepository
) {
    fun shareJournalToInstagram(journalId: Uuid) {
        // Share journal to Instagram with light theme
        sharingLauncher.shareJournalToInstagram(journalId, ShareTheme.Light)
    }
    
    fun shareJournalAsLink(journalId: Uuid) {
        // Share journal via system share sheet
        sharingLauncher.shareJournalLink(journalId)
    }
}
```

### Media Sharing

```kotlin
class MediaViewModel(private val sharingLauncher: SharingLauncher) {
    fun sharePhotoToInstagram(photoId: String) {
        // Share photo to Instagram feed
        sharingLauncher.sharePhotoToInstagramFeed(photoId)
    }
    
    fun shareVideoToInstagram(videoId: String) {
        // Share video to Instagram feed
        sharingLauncher.shareVideoToInstagramFeed(videoId)
    }
}
```

### Asset Generation

```kotlin
class SharePreviewViewModel(private val shareAssetGenerator: ShareAssetInterface) {
    suspend fun generatePreview(journal: Journal, theme: ShareTheme): Pair<String, String> {
        // Generate preview assets for sharing
        val background = shareAssetGenerator.generateBackgroundLayer(journal, theme)
        val sticker = shareAssetGenerator.generateStickerLayer(journal, theme)
        return background to sticker
    }
}
```

## Dependency Injection

```kotlin
val sharingModule = module {
    single<SharingLauncher> {
        when (getPlatform()) {
            Platform.ANDROID -> AndroidSharingLauncher(get(), get(), get(), get())
            Platform.IOS -> IosSharingLauncher(get(), get(), get())
            Platform.DESKTOP -> DesktopSharingLauncher(get(), get(), get())
        }
    }
    
    single<ShareAssetInterface> {
        when (getPlatform()) {
            Platform.ANDROID -> AndroidShareAssetGenerator(get())
            Platform.IOS -> IosShareAssetGenerator(get())
            Platform.DESKTOP -> DesktopShareAssetGenerator(get())
        }
    }
}
```

## TODOs

### Core Features
- [ ] Add link tracking capabilities
- [ ] Implement deep link generation
- [ ] Add share analytics tracking
- [ ] Implement QR code generation
- [ ] Add content preview generation

### Platform Integration
- [ ] Expand social media platform support
- [ ] Add Twitter/X integration
- [ ] Implement Facebook sharing
- [ ] Add LinkedIn sharing
- [ ] Implement email sharing

### Content Generation
- [ ] Improve asset generation quality
- [ ] Add animation support for shared content
- [ ] Implement video clip generation
- [ ] Add text overlay customization
- [ ] Implement share templates

### User Experience
- [ ] Add share history tracking
- [ ] Implement share result callbacks
- [ ] Add offline sharing capabilities
- [ ] Implement share scheduling
- [ ] Add share recipient suggestions
