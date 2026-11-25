package app.logdate.wear.e2e

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.RemoteCameraCaptureResultDataMapper
import app.logdate.client.sync.datalayer.RemoteCameraDeviceDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.wear.sync.WearDataLayerClient
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Checks that the watch's Data Layer listener is declared for everything the phone sends it.
 *
 * Play services starts the listener service only for an event whose action and URI match one of its
 * manifest filters, so a missing path means the event never arrives, whether or not the app is
 * running. Each event is resolved here the way Play services resolves it, against the installed
 * manifest.
 */
@RunWith(AndroidJUnit4::class)
class WearManifestFiltersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val noteId = Uuid.random()
    private val journalId = Uuid.random()

    @Test
    fun `every data item the phone writes reaches the listener`() {
        listOf(
            NoteDataMapper.notePath(noteId),
            NoteDataMapper.noteDeletePath(noteId),
            JournalDataMapper.journalPath(journalId),
            JournalDataMapper.journalDeletePath(journalId),
            AssociationDataMapper.associationPath(journalId, noteId),
            AssociationDataMapper.associationDeletePath(journalId, noteId),
            RemoteCameraDeviceDataMapper.PATH_CAMERA_DEVICES,
            RemoteCameraCaptureResultDataMapper.PATH_CAMERA_CAPTURE_RESULT,
            WearPaths.STATE,
            WearPaths.windowItem(app.logdate.client.sync.datalayer.v2.RecordKind.NOTE, noteId),
            WearPaths.windowTombstone(app.logdate.client.sync.datalayer.v2.RecordKind.NOTE, noteId),
        ).forEach { path -> assertResolvesToListener(DataClient.ACTION_DATA_CHANGED, path) }
    }

    @Test
    fun `every message the phone sends reaches the listener`() {
        listOf(
            WearAudioRequestPaths.noteAckPath(noteId),
            WearAudioRequestPaths.SYNC_REQUEST_PATH,
            WearPaths.RECEIPTS_PUSH,
        ).forEach { path -> assertResolvesToListener(MessageClient.ACTION_MESSAGE_RECEIVED, path) }
    }

    @Test
    fun `the phone coming into reach reaches the listener`() {
        assertResolvesToListener(CapabilityClient.ACTION_CAPABILITY_CHANGED, "/${WearDataLayerClient.PHONE_CAPABILITY}")
        assertResolvesToListener(CapabilityClient.ACTION_CAPABILITY_CHANGED, "/${WearProtocol.PHONE_CAPABILITY_V2}")
    }

    @Test
    fun `every request the phone sends reaches the listener`() {
        listOf(WearPaths.LINK_OFFER, WearPaths.LINK_BUNDLE, WearPaths.PRESENCE, WearPaths.WATCH_PING).forEach { path ->
            assertResolvesToListener(MessageClient.ACTION_REQUEST_RECEIVED, path)
        }
    }

    @Test
    fun `moving the watch to another phone reaches the listener`() {
        assertResolvesToListener(NodeClient.ACTION_NODE_MIGRATED, "")
    }

    private fun assertResolvesToListener(
        action: String,
        path: String,
    ) {
        val intent = Intent(action, "wear://$PHONE_NODE$path".toUri()).setPackage(context.packageName)
        val services = context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))

        assertEquals(listOf(LISTENER_SERVICE), services.map { it.serviceInfo.name }, "$action $path")
    }

    private companion object {
        const val PHONE_NODE = "phone-node"
        const val LISTENER_SERVICE = "app.logdate.wear.sync.WearDataLayerListenerService"
    }
}
