package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourcePage
import app.logdate.shared.model.identity.IdentityAdoptionSourceItemView
import app.logdate.shared.model.identity.IdentityAdoptionSourceKindView
import app.logdate.shared.model.identity.IdentityAdoptionSourcePageView
import java.util.Base64

/** The last included key resumes the original source scan; packing never discards an unseen row. */
internal fun identityAdoptionSourceTransportPage(page: IdentityAdoptionSourcePage): IdentityAdoptionSourcePageView {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val items = mutableListOf<IdentityAdoptionSourceItemView>()
    var bytes = 0L
    var next = page.next
    for (item in page.items) {
        val evidence = IdentityAdoptionSourceEvidence.canonicalItem(page.kind, item)
        val body = item.binary?.bytes
        val size = evidence.size.toLong() + (body?.size ?: 0)
        if (items.isNotEmpty() && bytes + size > SOURCE_PAGE_SOFT_BYTES) {
            next = page.items[items.lastIndex].key.toList()
            break
        }
        items += IdentityAdoptionSourceItemView(encoder.encodeToString(evidence), body?.let(encoder::encodeToString))
        bytes += size
    }
    return IdentityAdoptionSourcePageView(
        page.generation,
        page.fenced,
        IdentityAdoptionSourceKindView.valueOf(page.kind.name),
        page.present,
        page.count,
        items,
        next,
    )
}

private const val SOURCE_PAGE_SOFT_BYTES = 4L * 1024 * 1024
