package app.logdate.server.database

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Extension functions to handle UUID conversions between kotlin.uuid.Uuid and java.util.UUID
 * for compatibility with Exposed ORM which uses java.util.UUID internally.
 */

@OptIn(ExperimentalUuidApi::class)
fun Uuid.toJavaUUID(): UUID = UUID.fromString(this.toString())

@OptIn(ExperimentalUuidApi::class)
fun UUID.toKotlinUuid(): Uuid = Uuid.parse(this.toString())
