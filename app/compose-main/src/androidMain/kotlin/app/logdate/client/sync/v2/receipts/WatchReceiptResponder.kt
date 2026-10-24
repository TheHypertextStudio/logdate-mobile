package app.logdate.client.sync.v2.receipts

import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.ReceiptsRequest
import app.logdate.client.sync.datalayer.v2.ReceiptsResponse
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearProtocol
import io.github.aakira.napier.Napier

/** Answers a watch's receipts request with what this phone has of each record it asks about. */
class WatchReceiptResponder(
    private val book: PhoneReceiptBook,
) {
    suspend fun answer(
        sourceNodeId: String,
        payload: ByteArray,
    ): ByteArray {
        val request =
            when (val decoded = WearJson.decode(payload, WearProtocol.RECEIPTS_SCHEMA, ReceiptsRequest.serializer())) {
                is Decoded.Supported -> {
                    decoded.value
                }

                is Decoded.Unsupported -> {
                    Napier.w("A watch asked for receipts with schema ${decoded.schema}; it gets none until this phone updates")
                    return encodeUntrusted()
                }

                is Decoded.Malformed -> {
                    Napier.e("A watch's receipts request could not be read: ${decoded.reason}")
                    return encodeUntrusted()
                }
            }
        val context = request.context ?: return encodeUntrusted()
        if (context.watchNode != sourceNodeId || !book.isCurrent(context)) return encodeUntrusted()
        val receipts = request.items.mapNotNull { book.receiptFor(context, it) }
        if (!book.isCurrent(context)) return encodeUntrusted()
        return WearJson.encodeToBytes(
            ReceiptsResponse.serializer(),
            ReceiptsResponse(
                cloud = if (context.mode == LinkMode.CLOUD) CloudPresence.SIGNED_IN else CloudPresence.NO_ACCOUNT,
                receipts = receipts,
                context = context,
            ),
        )
    }

    private fun encodeUntrusted(): ByteArray =
        WearJson.encodeToBytes(
            ReceiptsResponse.serializer(),
            ReceiptsResponse(cloud = CloudPresence.UNKNOWN, receipts = emptyList()),
        )
}
