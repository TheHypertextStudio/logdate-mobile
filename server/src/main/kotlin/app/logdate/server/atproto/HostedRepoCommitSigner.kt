package app.logdate.server.atproto

import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.isSyntheticRepoDid
import app.logdate.shared.model.AccountIdentityKind
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.DigestRepoCommitSigner
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoCommitSigner
import studio.hypertext.atproto.repo.RepoSigningKeyUnavailableException
import java.util.Base64
import kotlin.uuid.ExperimentalUuidApi

/**
 * Repo signer backed by the hosted account signing keys already provisioned for DID documents.
 *
 * Relays and other services check each commit against the signing key in the account's DID
 * document, so a commit is only useful with a real signature from that key. When the account or
 * its key cannot be found, signing throws [RepoSigningKeyUnavailableException] and the write fails;
 * it never stores a commit with something else in place of a signature, and it never creates a
 * key the DID document does not list.
 *
 * The one exception is sync storage for an account without a DID of its own
 * ([isSyntheticRepoDid]). No account owns that DID and no DID document names it, so it has no key
 * to sign with and can never be published; its commits carry [DigestRepoCommitSigner]'s hash.
 */
@OptIn(ExperimentalUuidApi::class)
class HostedRepoCommitSigner(
    private val accountRepository: AccountRepository,
    private val signingKeyService: SigningKeyService,
) : RepoCommitSigner {
    override suspend fun sign(
        commit: RepoCommit,
        payload: ByteArray,
    ): String {
        if (isSyntheticRepoDid(commit.repo)) return DigestRepoCommitSigner.sign(commit, payload)
        val account =
            accountRepository.findByDid(commit.repo.toString())
                ?: throw unavailable(commit.repo, "no account owns this repository")
        if (account.identityKind != AccountIdentityKind.HOSTED) throw unavailable(commit.repo, "the identity is not hosted here")
        val activeKey =
            signingKeyService.findActiveKey(account.id)
                ?: throw unavailable(commit.repo, "the account has no active signing key")
        val curve =
            EcCurve.fromSigningKeyAlgorithm(activeKey.algorithm)
                ?: throw unavailable(commit.repo, "the active signing key uses unsupported algorithm ${activeKey.algorithm}")
        val privateKey = signingKeyService.decryptPrivateKey(activeKey)
        val signature = EcKeySupport.signSha256(privateKey, curve, payload)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    override suspend fun verify(
        commit: RepoCommit,
        payload: ByteArray,
        signature: String,
    ): Boolean {
        if (isSyntheticRepoDid(commit.repo)) return DigestRepoCommitSigner.verify(commit, payload, signature)
        val account =
            accountRepository.findByDid(commit.repo.toString())
                ?: throw unavailable(commit.repo, "no account owns this repository")
        if (account.identityKind != AccountIdentityKind.HOSTED) throw unavailable(commit.repo, "the identity is not hosted here")
        val publicKeyMultibase =
            account.signingKeyPublic
                ?: throw unavailable(commit.repo, "the account has no published signing key")
        val decoded =
            runCatching { EcKeySupport.decodePublicKey(publicKeyMultibase) }
                .getOrElse { cause -> throw unavailable(commit.repo, "the published signing key cannot be read (${cause.message})") }
        // A signature that is not base64url, or not the size the curve produces, is simply not a
        // valid signature for this commit.
        return try {
            EcKeySupport.verifySha256(
                publicKey = decoded.publicKey,
                curve = decoded.curve,
                payload = payload,
                signature = Base64.getUrlDecoder().decode(signature),
            )
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun unavailable(
        repo: AtprotoDid,
        reason: String,
    ) = RepoSigningKeyUnavailableException(repo = repo, reason = reason)
}
