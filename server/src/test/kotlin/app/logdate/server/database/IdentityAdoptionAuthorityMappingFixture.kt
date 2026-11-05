package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionDisposition
import app.logdate.server.identity.adoption.IdentityAdoptionMappingRequest
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import java.util.HexFormat
import kotlin.test.assertNotNull

internal suspend fun IdentityAdoptionAuthorizedFixture.mapStorageAuthority(current: IdentityAdoptionOperation) {
    val kind = IdentityAdoptionSourceKind.STORAGE_AUTHORITY
    val item = assertNotNull(PostgreSQLIdentityAdoptionSourceInventory(source).page(current, kind, now = clock.now())).items.single()
    val request =
        IdentityAdoptionMappingRequest(
            IdentityAdoptionSourceReference(kind, item.key),
            HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(kind, item)),
            IdentityAdoptionDisposition.STRUCTURAL,
            emptyList(),
            emptyList(),
        )
    val ticket =
        assertNotNull(
            actions.issue(
                current,
                IdentityAdoptionAction.STAGE_RECORDS,
                prepareIdentityAdoptionMapping(request).bodyDigest,
                clock.now(),
            ),
        )
    assertNotNull(
        PostgreSQLIdentityAdoptionMappings(source).put(
            current,
            ticket,
            actionSignature(ticket, current),
            request,
            Long.MAX_VALUE,
            clock.now(),
        ),
    )
}
