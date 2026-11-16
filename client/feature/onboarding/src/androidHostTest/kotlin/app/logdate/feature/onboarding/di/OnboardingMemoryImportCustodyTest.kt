package app.logdate.feature.onboarding.di

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.media.ManagedMediaPublicationCustody
import app.logdate.client.media.MediaManager
import app.logdate.client.media.StagedMediaFile
import app.logdate.feature.onboarding.ui.SelectedMemoryMediaImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

/**
 * A memory chosen during onboarding is copied into the journal before the first entry claims it, so
 * the Android import has to hold the copy in publication custody until then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class OnboardingMemoryImportCustodyTest {
    private var koin: KoinApplication? = null

    @After
    fun tearDown() {
        koin?.close()
    }

    @Test
    fun `a memory imported during onboarding stays in custody until its entry claims it`() {
        Robolectric.setupContentProvider(SelectedPhotos::class.java, SelectedPhotos.AUTHORITY)
        val custody = RecordingCustody()
        val app =
            koinApplication {
                androidContext(RuntimeEnvironment.getApplication())
                modules(
                    onboardingPlatformModule,
                    module {
                        single<MediaManager> { InMemoryMediaManager() }
                        single<ManagedMediaPublicationCustody> { custody }
                    },
                )
            }
        koin = app

        val managedUri = runBlocking { app.koin.get<SelectedMemoryMediaImporter>().import(SelectedPhotos.PHOTO) }

        assertEquals(listOf(managedUri), custody.published)
    }

    private class RecordingCustody : ManagedMediaPublicationCustody {
        val published = mutableListOf<String>()

        override suspend fun reserve(
            destination: String,
            stagedMedia: StagedMediaFile,
        ): String = "ticket-${published.size}"

        override suspend fun publish(
            ticketId: String,
            reference: String,
        ) {
            published += reference
        }

        override suspend fun acknowledge(
            ticketId: String,
            reference: String,
        ) = Unit
    }

    /** One JPEG offered the way the system photo picker offers a selection. */
    class SelectedPhotos : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            val photo = File.createTempFile("selected-memory", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            return ParcelFileDescriptor.open(photo, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "app.logdate.test.selectedphotos"
            const val PHOTO = "content://$AUTHORITY/memory/1"
        }
    }
}
