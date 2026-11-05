package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountModeration
import app.logdate.server.identity.StoredSigningKey
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionSigningKeysPostgresTest {
    @Test fun holdRefusesNewKey() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { keys.save(next()) }
        }

    @Test fun holdRefusesKeyReplacement() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { keys.replaceActiveKey(next()) }
        }

    @Test fun holdRefusesKeyRevocation() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { keys.revokeActiveKeys(owner.id) }
        }

    @Test fun inactiveAccountCannotReplaceKey() =
        withFixture {
            assertTrue(accounts.deactivateAccount(owner.id))
            refuses(IdentityFenceOutcome.DELETED) { keys.replaceActiveKey(next()) }
        }

    @Test fun moderatedAccountCannotRevokeKey() =
        withFixture {
            assertTrue(accounts.setModeration(owner.id, AccountModeration.SUSPENDED))
            refuses(IdentityFenceOutcome.DELETED) { keys.revokeActiveKeys(owner.id) }
        }

    @Test fun missingEpochCannotReplaceKeyAfterAuthorityAdvances() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { keys.replaceActiveKey(next()) }
        }

    @Test fun callerCannotBorrowAnotherEpochToWriteKeys() =
        withFixture {
            refuses(IdentityFenceOutcome.CHANGED) { keys.replaceActiveKey(next(), identityEpoch = 1) }
        }

    @Test fun heldKeyCiphertextCannotBeRewrapped() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) {
                keys.replaceEncryptedPrivateKey(original.id.toString(), original.privateKeyEncrypted, "synthetic-rewrapped")
            }
        }

    @Test fun externalIdentityCannotCreateHostedAuthorityEvenAtCurrentEpoch() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_kind = 'external', storage_mode = ? WHERE id = ?").use {
                    it.setString(1, AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
                    it.setObject(2, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { keys.replaceActiveKey(next()) }
        }

    @Test fun retiredDidCannotRegainSigningAuthority() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, " +
                        "identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, owner.did)
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { keys.replaceActiveKey(next()) }
        }

    @Test fun keyWriteStartedBeforeHoldRechecksAfterWaitingForAccount() =
        withFixture {
            coroutineScope {
                source.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE").use {
                        it.setObject(1, owner.id.toJavaUUID())
                        it.executeQuery().close()
                    }
                    val writer = async(Dispatchers.IO) { runCatching { PostgreSQLSigningKeyRepository().replaceActiveKey(next()) } }
                    try {
                        withTimeout(5_000) { while (waitingOnAccount() == 0) delay(20) }
                        val now = Clock.System.now()
                        control
                            .prepareStatement(
                                "INSERT INTO identity_adoption_operations(id, account_id, source_did, target_did, device_id, " +
                                    "source_epoch, revision, phase, lease_expires_at, created_at, updated_at) " +
                                    "VALUES (?, ?, ?, 'did:web:target.test', 'device', 0, 1, 'FENCED', ?, ?, ?)",
                            ).use {
                                it.setString(1, Uuid.random().toString())
                                it.setObject(2, owner.id.toJavaUUID())
                                it.setString(3, owner.did)
                                it.setTimestamp(4, (now + 120.seconds).timestamp())
                                it.setTimestamp(5, now.timestamp())
                                it.setTimestamp(6, now.timestamp())
                                assertEquals(1, it.executeUpdate())
                            }
                        control.commit()
                        val result = withTimeout(5_000) { writer.await() }
                        assertEquals(IdentityFenceOutcome.HELD, assertIs<IdentityWriteRefusedException>(result.exceptionOrNull()).outcome)
                        assertEquals(original, keys.findActiveByAccountId(owner.id))
                    } finally {
                        control.rollback()
                        withContext(NonCancellable) { withTimeout(5_000) { writer.await() } }
                    }
                }
            }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val original: StoredSigningKey,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val keys = PostgreSQLSigningKeyRepository()

        fun next() = original.copy(id = Uuid.random(), publicKeyMultibase = "zNext", privateKeyEncrypted = "synthetic-next")

        suspend fun refuses(
            outcome: IdentityFenceOutcome,
            mutate: suspend () -> Any,
        ) {
            assertEquals(outcome, assertFailsWith<IdentityWriteRefusedException> { mutate() }.outcome)
            assertEquals(original, keys.findActiveByAccountId(owner.id))
            val count =
                source.blueskyTransaction {
                    prepareStatement("SELECT COUNT(*) FROM signing_keys WHERE account_id = ?").use {
                        it.setObject(1, owner.id.toJavaUUID())
                        it.executeQuery().use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    }
                }
            assertEquals(1, count)
        }

        suspend fun waitingOnAccount(): Int =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' " +
                        "AND query LIKE '%SELECT identity_epoch, is_active, moderation%'",
                ).use {
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }

        suspend fun hold() {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            val binding = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0)
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(binding, Clock.System.now()),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
        }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Key owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            val key =
                StoredSigningKey(
                    Uuid.random(),
                    id,
                    publicKeyMultibase = "zOriginal",
                    privateKeyEncrypted = "synthetic-original",
                    createdAt = Clock.System.now(),
                )
            PostgreSQLSigningKeyRepository().save(key)
            val stored = assertNotNull(PostgreSQLSigningKeyRepository().findActiveByAccountId(id))
            Fixture(source, owner, stored).block()
        }
}
