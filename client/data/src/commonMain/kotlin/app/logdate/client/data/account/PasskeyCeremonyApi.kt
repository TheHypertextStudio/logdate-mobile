package app.logdate.client.data.account

import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest

/** A server-bound ceremony can supply challenges without owning session or journal-key installation. */
internal interface PasskeyCeremonyApi {
    val originalOwnerId: String? get() = null

    val backendOrigin: String

    suspend fun beginRegistration(request: BeginAccountCreationRequest): Result<BeginAccountCreationData>

    suspend fun completeRegistration(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData>

    suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData>

    suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData>
}
