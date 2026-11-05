package app.logdate.server.database

import app.logdate.server.space.SpaceChange
import app.logdate.server.space.decodeRecord
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq

internal fun spaceStateKey(
    space: String,
    author: String,
): Op<Boolean> = (SpaceStatesTable.spaceUri eq space) and (SpaceStatesTable.authorDid eq author)

internal fun spaceRecordKey(
    space: String,
    author: String,
    rkey: String,
): Op<Boolean> = (SpaceRecordsTable.spaceUri eq space) and (SpaceRecordsTable.authorDid eq author) and (SpaceRecordsTable.rkey eq rkey)

internal fun spaceRecordBlobKey(
    space: String,
    author: String,
    rkey: String,
): Op<Boolean> =
    (SpaceRecordBlobsTable.spaceUri eq space) and (SpaceRecordBlobsTable.authorDid eq author) and (SpaceRecordBlobsTable.rkey eq rkey)

internal fun ResultRow.toSpaceChange(): SpaceChange =
    SpaceChange(
        rkey = this[SpaceRecordsTable.rkey],
        seq = this[SpaceRecordsTable.seq],
        deleted = this[SpaceRecordsTable.deleted],
        cid = this[SpaceRecordsTable.cid],
        record = this[SpaceRecordsTable.record]?.let(::decodeRecord),
    )
