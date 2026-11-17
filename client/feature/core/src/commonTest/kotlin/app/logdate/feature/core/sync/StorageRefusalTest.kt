package app.logdate.feature.core.sync

import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.EntitlementTierWire
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which limit a storage refusal is put down to, from the account's plan. */
class StorageRefusalTest {
    private val gib = 1024L * 1024L * 1024L

    private fun entitlement(
        planId: String,
        status: EntitlementStatusWire = EntitlementStatusWire.ACTIVE,
        storage: Long? = 50 * gib,
        extraStep: Long? = null,
    ) = EntitlementResponse(
        planId = planId,
        tier = EntitlementTierWire.STANDARD,
        status = status,
        storageBytesLimit = storage,
        backupCountLimit = null,
        extraStorageStepBytes = extraStep,
    )

    @Test
    fun `the base plan is full at what it includes`() {
        assertEquals(StorageRefusal.Full(includedBytes = 50 * gib), StorageRefusal.of(entitlement("cloud")))
    }

    @Test
    fun `an account with no plan is refused for having none`() {
        val none = entitlement(EntitlementResponse.NO_PLAN_ID, status = EntitlementStatusWire.CANCELLED, storage = 0)

        assertEquals(StorageRefusal.NoPlan, StorageRefusal.of(none))
    }

    @Test
    fun `Plus with a failing payment is refused until the payment is fixed`() {
        val pastDue = entitlement("cloud_plus", status = EntitlementStatusWire.PAST_DUE, storage = 250 * gib, extraStep = 50 * gib)

        assertEquals(StorageRefusal.PaymentDue, StorageRefusal.of(pastDue))
    }

    @Test
    fun `a past-due base plan is still simply full`() {
        val pastDue = entitlement("cloud", status = EntitlementStatusWire.PAST_DUE)

        assertEquals(StorageRefusal.Full(includedBytes = 50 * gib), StorageRefusal.of(pastDue))
    }
}
