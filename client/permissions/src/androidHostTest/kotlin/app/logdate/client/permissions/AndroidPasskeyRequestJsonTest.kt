package app.logdate.client.permissions

import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyUser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WebAuthn describes `allowCredentials` and `excludeCredentials` as lists of credential
 * descriptors, not lists of ids. Credential Manager cannot match a bare string against a stored
 * passkey, so getting this shape wrong makes every sign-in fail with "No credentials available"
 * no matter how healthy the credential is.
 */
class AndroidPasskeyRequestJsonTest {
    @Test
    fun `new credentials request PRF support for later encrypted unlock`() {
        val request =
            Json
                .parseToJsonElement(
                    buildRegistrationRequestJson(
                        PasskeyRegistrationOptions(
                            challenge = "AQ",
                            rpId = "logdate.app",
                            rpName = "LogDate",
                            user = PasskeyUser("AQ", "fixture", "Fixture"),
                        ),
                    ),
                ).jsonObject
        assertEquals(
            "{}",
            request
                .getValue("extensions")
                .jsonObject
                .getValue("prf")
                .toString(),
        )
    }

    @Test
    fun `unlock request evaluates the same local PRF input as the native Mac`() {
        val request =
            Json
                .parseToJsonElement(
                    buildAuthenticationRequestJson(
                        PasskeyAuthenticationOptions(challenge = "AQ", rpId = "logdate.app"),
                        requestUnlock = true,
                    ),
                ).jsonObject
        val first =
            request
                .getValue("extensions")
                .jsonObject
                .getValue("prf")
                .jsonObject
                .getValue("eval")
                .jsonObject
                .getValue("first")
                .jsonPrimitive.content
        assertEquals(PasskeyUnlockInput.first, first)
    }

    @Test
    fun `authentication request describes each allowed credential`() {
        val json =
            Json
                .parseToJsonElement(
                    buildAuthenticationRequestJson(
                        PasskeyAuthenticationOptions(
                            challenge = "Y2hhbGxlbmdl",
                            rpId = "logdate.app",
                            allowCredentials = listOf("f0DYSBRBUEBAjlbaF9dOng"),
                        ),
                    ),
                ).jsonObject

        val descriptor =
            json
                .getValue("allowCredentials")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("public-key", descriptor.getValue("type").jsonPrimitive.content)
        assertEquals("f0DYSBRBUEBAjlbaF9dOng", descriptor.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `registration request describes each excluded credential`() {
        val json =
            Json
                .parseToJsonElement(
                    buildRegistrationRequestJson(
                        PasskeyRegistrationOptions(
                            challenge = "Y2hhbGxlbmdl",
                            rpId = "logdate.app",
                            rpName = "LogDate",
                            user = PasskeyUser(id = "dXNlcg", name = "alice", displayName = "Alice"),
                            excludeCredentials = listOf("f0DYSBRBUEBAjlbaF9dOng"),
                        ),
                    ),
                ).jsonObject

        val descriptor =
            json
                .getValue("excludeCredentials")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("public-key", descriptor.getValue("type").jsonPrimitive.content)
        assertEquals("f0DYSBRBUEBAjlbaF9dOng", descriptor.getValue("id").jsonPrimitive.content)
    }
}
