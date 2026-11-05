package app.logdate.server.identity.adoption

internal class IdentityAdoptionRuntime(
    val repository: IdentityAdoptionRepository,
    val oauth: IdentityAdoptionOAuthService,
    val confirmation: IdentityAdoptionConfirmationService,
    val lifecycle: IdentityAdoptionLifecycle,
    val finalization: IdentityAdoptionFinalization,
    val sourceAccess: IdentityAdoptionSourceAccess,
    val copyAccess: IdentityAdoptionCopyAccess,
    val maintenance: IdentityAdoptionMaintenance,
    private val stagingMaintenance: IdentityAdoptionMaintenance? = null,
) : AutoCloseable {
    fun start() {
        maintenance.start()
        stagingMaintenance?.start()
    }

    override fun close() {
        try {
            stagingMaintenance?.close()
        } finally {
            maintenance.close()
        }
    }
}
