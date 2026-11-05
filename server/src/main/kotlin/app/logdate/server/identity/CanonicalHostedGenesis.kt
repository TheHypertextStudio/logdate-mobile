package app.logdate.server.identity

/** Reuse signed bytes; a changed configuration must not mint another identity after a crash. */
internal fun canonicalHostedGenesis(
    history: List<StoredHostedPlcOperation>,
    epoch: Long,
): StoredHostedPlcOperation? {
    if (history.isEmpty()) return null
    val genesis = history.filter { it.prevCid == null }.distinctBy { Triple(it.did, it.operationJson, it.cid) }
    if (genesis.size != 1 || history.any { it.identityEpoch != epoch }) {
        throw IdentityLifecycleConflictException("Hosted identity history needs reconciliation")
    }
    return genesis.single()
}
