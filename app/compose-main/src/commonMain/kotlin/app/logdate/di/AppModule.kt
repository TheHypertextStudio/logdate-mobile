package app.logdate.di

import app.logdate.feature.core.account.di.accountFeatureModule
import app.logdate.feature.core.di.coreFeatureModule
import app.logdate.feature.editor.di.editorFeatureModule
import app.logdate.feature.events.di.eventsFeatureModule
import app.logdate.feature.journals.di.journalsFeatureModule
import app.logdate.feature.library.di.libraryFeatureModule
import app.logdate.feature.location.timeline.di.locationTimelineFeatureModule
import app.logdate.feature.onboarding.di.onboardingFeatureModule
import app.logdate.feature.postcards.di.postcardsFeatureModule
import app.logdate.feature.rewind.di.rewindFeatureModule
import app.logdate.feature.search.di.searchFeatureModule
import app.logdate.feature.stickers.di.stickersFeatureModule
import app.logdate.feature.timeline.di.timelineFeatureModule
import org.koin.core.module.Module

/**
 * The main module for the application.
 *
 * This module is used to provide the dependencies for the application. Each source set will provide
 * a different implementation of this module.
 *
 * Implementers should include [defaultModules] in their implementation.
 */
expect val appModule: Module

/**
 * A list of modules that are included by default in the application.
 *
 * This includes all feature modules.
 */
internal val defaultModules: Set<Module> =
    setOf(
        coreFeatureModule,
        accountFeatureModule,
        onboardingFeatureModule,
        editorFeatureModule,
        timelineFeatureModule,
        rewindFeatureModule,
        journalsFeatureModule,
        libraryFeatureModule,
        locationTimelineFeatureModule,
        searchFeatureModule,
        postcardsFeatureModule,
        stickersFeatureModule,
        eventsFeatureModule,
    )
