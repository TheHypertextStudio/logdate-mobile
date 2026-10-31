@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.devices.AccountDeviceRepository
import app.logdate.shared.model.RegisterDeviceRequest
import app.logdate.shared.model.RegisteredDevice
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

class PostgreSQLAccountDeviceRepository : AccountDeviceRepository {
    override suspend fun list(accountId: Uuid): List<RegisteredDevice> =
        dbQuery {
            AccountDevicesTable
                .selectAll()
                .where { AccountDevicesTable.accountId eq accountId.toJavaUUID() }
                .orderBy(AccountDevicesTable.lastActive, SortOrder.DESC)
                .map { it.toDevice() }
        }

    override suspend fun register(
        accountId: Uuid,
        deviceId: Uuid,
        request: RegisterDeviceRequest,
        now: Long,
    ): RegisteredDevice =
        dbQuery {
            val owner = accountId.toJavaUUID()
            val id = deviceId.toJavaUUID()
            AccountDevicesTable.insertIgnore {
                it[AccountDevicesTable.accountId] = owner
                it[AccountDevicesTable.deviceId] = id
                it[name] = request.name
                it[platform] = request.platform
                it[appVersion] = request.appVersion
                it[createdAt] = now
                it[lastActive] = now
            }
            val row =
                AccountDevicesTable
                    .selectAll()
                    .where {
                        (AccountDevicesTable.accountId eq owner) and (AccountDevicesTable.deviceId eq id)
                    }.forUpdate()
                    .single()
            AccountDevicesTable.update({ (AccountDevicesTable.accountId eq owner) and (AccountDevicesTable.deviceId eq id) }) {
                it[name] = request.name
                it[platform] = request.platform
                it[appVersion] = request.appVersion
                it[lastActive] = maxOf(row[AccountDevicesTable.lastActive], now)
            }
            row.toDevice().copy(
                name = request.name,
                platform = request.platform,
                appVersion = request.appVersion,
                lastActive = maxOf(row[AccountDevicesTable.lastActive], now),
            )
        }

    private fun ResultRow.toDevice() =
        RegisteredDevice(
            this[AccountDevicesTable.deviceId].toString(),
            this[AccountDevicesTable.name],
            this[AccountDevicesTable.platform],
            this[AccountDevicesTable.appVersion],
            this[AccountDevicesTable.createdAt],
            this[AccountDevicesTable.lastActive],
        )
}
