package app.logdate.tools.passkeyprovider

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

/**
 * Performs the WebAuthn ceremony the user selected in the Credential Manager sheet.
 *
 * Real authenticators prompt for biometrics here. This one asserts user verification and completes
 * immediately, which is the entire point: it makes the Credential Manager path runnable on an
 * emulator with no Google account and no enrolled biometric.
 */
class CeremonyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            when (intent.action) {
                ACTION_CREATE -> handleCreate()
                ACTION_GET -> handleGet()
                else -> setResult(RESULT_CANCELED)
            }
        }.onFailure { error ->
            Log.e(TAG, "Ceremony failed", error)
            failCurrentCeremony(error)
        }
        finish()
    }

    private fun handleCreate() {
        val request =
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
                ?: error("missing ProviderCreateCredentialRequest")
        val callingRequest =
            request.callingRequest as? CreatePublicKeyCredentialRequest
                ?: error("unsupported create request type")

        val options = JSONObject(callingRequest.requestJson)
        val rpId = options.getJSONObject("rp").getString("id")
        val challenge = options.getString("challenge")
        val user = options.getJSONObject("user")
        val userHandle = user.getString("id")
        val userName = user.getString("name")

        val keyPair = WebAuthnCrypto.generateKeyPair()
        val credentialId = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val clientDataJson = clientData("webauthn.create", challenge, request.callingAppInfo.packageName)

        val authData =
            WebAuthnCrypto.authenticatorData(
                rpId = rpId,
                signCount = 0,
                credentialId = credentialId,
                publicKey = keyPair.public as ECPublicKey,
            )

        CredentialStore(this).save(
            StoredCredential(
                credentialId = credentialId.b64u(),
                rpId = rpId,
                userName = userName,
                userHandle = userHandle,
                privateKeyPkcs8 = keyPair.private.encoded.b64u(),
                signCount = 0,
            ),
        )

        val responseJson =
            JSONObject()
                .put("id", credentialId.b64u())
                .put("rawId", credentialId.b64u())
                .put("type", "public-key")
                .put("authenticatorAttachment", "platform")
                .put(
                    "response",
                    JSONObject()
                        .put("clientDataJSON", clientDataJson.b64u())
                        .put("attestationObject", WebAuthnCrypto.attestationObject(authData).b64u())
                        .put("transports", org.json.JSONArray(listOf("internal"))),
                ).put("clientExtensionResults", JSONObject())
                .toString()

        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePublicKeyCredentialResponse(responseJson))
        setResult(RESULT_OK, result)
    }

    private fun handleGet() {
        val request =
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
                ?: error("missing ProviderGetCredentialRequest")
        val option =
            request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
                ?: error("no public key credential option")

        val options = JSONObject(option.requestJson)
        val rpId = options.getString("rpId")
        val challenge = options.getString("challenge")

        val store = CredentialStore(this)
        val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID)
        val credential =
            credentialId?.let(store::find)
                ?: store.forRpId(rpId).firstOrNull()
                ?: error("no stored credential for $rpId")

        val clientDataJson = clientData("webauthn.get", challenge, request.callingAppInfo.packageName)
        val nextSignCount = credential.signCount + 1
        val authData = WebAuthnCrypto.authenticatorData(rpId = rpId, signCount = nextSignCount)
        val signature =
            WebAuthnCrypto.sign(
                privateKey = WebAuthnCrypto.privateKeyFrom(credential.privateKeyPkcs8.unb64u()),
                authData = authData,
                clientDataJson = clientDataJson,
            )
        store.save(credential.copy(signCount = nextSignCount))

        val responseJson =
            JSONObject()
                .put("id", credential.credentialId)
                .put("rawId", credential.credentialId)
                .put("type", "public-key")
                .put("authenticatorAttachment", "platform")
                .put(
                    "response",
                    JSONObject()
                        .put("clientDataJSON", clientDataJson.b64u())
                        .put("authenticatorData", authData.b64u())
                        .put("signature", signature.b64u())
                        .put("userHandle", credential.userHandle),
                ).put("clientExtensionResults", JSONObject())
                .toString()

        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            GetCredentialResponse(PublicKeyCredential(responseJson)),
        )
        setResult(RESULT_OK, result)
    }

    /**
     * Android clientDataJSON uses an `android:apk-key-hash:<base64url-SHA256(signing cert)>` origin
     * derived from the *calling* app's signature. The relying party matches this against its
     * configured origin allowlist, so computing it from the real certificate is what makes the
     * ceremony verifiable server-side rather than a mock.
     */
    private fun clientData(
        type: String,
        challenge: String,
        callingPackage: String,
    ): ByteArray {
        val json =
            JSONObject()
                .put("type", type)
                .put("challenge", challenge)
                .put("origin", apkKeyHashOrigin(callingPackage))
                .put("androidPackageName", callingPackage)
                .toString()
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Optional origin override, used to exercise relying parties whose allowlist does not yet carry
     * this build's signing certificate. Write it with:
     *
     * ```
     * adb shell run-as app.logdate.tools.passkeyprovider \
     *   sh -c 'echo -n https://example.com > files/origin_override'
     * ```
     */
    private fun overrideOrigin(): String? =
        runCatching { File(filesDir, ORIGIN_OVERRIDE_FILE).takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun apkKeyHashOrigin(callingPackage: String): String {
        overrideOrigin()?.let { return it }
        val signatures =
            packageManager
                .getPackageInfo(callingPackage, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo
                ?.apkContentsSigners
                ?: error("no signing certificates for $callingPackage")
        val digest = MessageDigest.getInstance("SHA-256").digest(signatures.first().toByteArray())
        return "android:apk-key-hash:${digest.b64u()}"
    }

    private fun failCurrentCeremony(error: Throwable) {
        val result = Intent()
        when (intent.action) {
            ACTION_CREATE -> {
                PendingIntentHandler.setCreateCredentialException(
                    result,
                    CreateCredentialUnknownException(error.message),
                )
            }

            else -> {
                PendingIntentHandler.setGetCredentialException(
                    result,
                    GetCredentialUnknownException(error.message),
                )
            }
        }
        setResult(RESULT_OK, result)
    }

    companion object {
        const val ACTION_CREATE = "app.logdate.tools.passkeyprovider.CREATE"
        const val ACTION_GET = "app.logdate.tools.passkeyprovider.GET"
        const val EXTRA_CREDENTIAL_ID = "credentialId"
        const val EXTRA_CALLING_PACKAGE = "callingPackage"
        private const val ORIGIN_OVERRIDE_FILE = "origin_override"
        private const val TAG = "TestPasskeyProvider"
    }
}
