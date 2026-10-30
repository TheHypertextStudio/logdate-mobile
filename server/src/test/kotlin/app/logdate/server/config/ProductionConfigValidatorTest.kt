package app.logdate.server.config

import app.logdate.server.config.ProductionConfigValidator.InsecureProductionConfigException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests the [profileAwareBoolEnv] utility function for resolving boolean environment variables
 * with profile-specific defaults.
 */
class ProfileAwareBoolEnvTest {
    @Test
    fun `unset env picks productionDefault in production`() {
        val v =
            profileAwareBoolEnv(
                name = "SOME_FLAG",
                productionDefault = true,
                devDefault = false,
                readEnv = { null },
                profile = RuntimeProfile.PRODUCTION,
            )
        assertTrue(v)
    }

    @Test
    fun `unset env picks devDefault outside production`() {
        val v =
            profileAwareBoolEnv(
                name = "SOME_FLAG",
                productionDefault = true,
                devDefault = false,
                readEnv = { null },
                profile = RuntimeProfile.DEVELOPMENT,
            )
        assertTrue(!v)
    }

    @Test
    fun `explicit true wins in any profile`() {
        val v =
            profileAwareBoolEnv(
                name = "SOME_FLAG",
                productionDefault = false,
                devDefault = false,
                readEnv = { "true" },
                profile = RuntimeProfile.PRODUCTION,
            )
        assertTrue(v)
    }

    @Test
    fun `explicit false wins even in production`() {
        val v =
            profileAwareBoolEnv(
                name = "SOME_FLAG",
                productionDefault = true,
                devDefault = true,
                readEnv = { "false" },
                profile = RuntimeProfile.PRODUCTION,
            )
        assertTrue(!v)
    }
}

/**
 * Tests the security validation logic for server configurations.
 *
 * Ensures that the server correctly enforces strict security requirements when
 * running in a production profile, such as mandatory high-entropy secrets and
 * database passwords, while allowing more flexible configurations for development
 * and testing environments.
 */
class ProductionConfigValidatorTest {
    @Test
    fun `development profile skips validation`() {
        // No env vars set — should not throw, because only production enforces secrets.
        ProductionConfigValidator.validate(profile = RuntimeProfile.DEVELOPMENT, readEnv = { null })
    }

    @Test
    fun `test profile skips validation`() {
        ProductionConfigValidator.validate(profile = RuntimeProfile.TEST, readEnv = { null })
    }

    @Test
    fun `production requires JWT_SECRET`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnvWithout("JWT_SECRET"),
                )
            }
        assertTrue(failure.message!!.contains("JWT_SECRET is required"))
    }

    @Test
    fun `production requires each dedicated AT Protocol secret even when JWT_SECRET is set`() {
        listOf(AtprotoSigningKeyKek, AtprotoSessionSecret).forEach { secret ->
            val failure =
                assertFailsWith<InsecureProductionConfigException> {
                    ProductionConfigValidator.validate(
                        profile = RuntimeProfile.PRODUCTION,
                        readEnv = productionEnvWithout(secret.envVar),
                    )
                }
            assertTrue(failure.message!!.contains("${secret.envVar} is required"), secret.envVar)
        }
    }

    @Test
    fun `production rejects short or placeholder dedicated AT Protocol secrets`() {
        listOf(AtprotoSigningKeyKek, AtprotoSessionSecret).forEach { secret ->
            mapOf(
                secret.developmentValue to "at least 32",
                "your-secret-key-change-in-production" to "known placeholder",
            ).forEach { (value, expected) ->
                val failure =
                    assertFailsWith<InsecureProductionConfigException> {
                        ProductionConfigValidator.validate(
                            profile = RuntimeProfile.PRODUCTION,
                            readEnv = productionEnv(secret.envVar to value),
                        )
                    }
                assertTrue(failure.message!!.contains("${secret.envVar} ") && failure.message!!.contains(expected), value)
            }
        }
    }

    @Test
    fun `production rejects a dedicated AT Protocol secret that reuses JWT_SECRET`() {
        listOf(AtprotoSigningKeyKek, AtprotoSessionSecret).forEach { secret ->
            val failure =
                assertFailsWith<InsecureProductionConfigException> {
                    ProductionConfigValidator.validate(
                        profile = RuntimeProfile.PRODUCTION,
                        readEnv = productionEnv(secret.envVar to VALID_JWT_SECRET),
                    )
                }
            assertTrue(failure.message!!.contains("${secret.envVar} must differ from JWT_SECRET"), secret.envVar)
        }
    }

    @Test
    fun `production rejects the signing-key KEK and session secret sharing one value`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv(AtprotoSessionSecret.envVar to VALID_SIGNING_KEY_KEK),
                )
            }
        assertTrue(failure.message!!.contains("${AtprotoSessionSecret.envVar} must differ from ${AtprotoSigningKeyKek.envVar}"))
    }

    @Test
    fun `production accepts a separate decrypt-only signing-key KEK`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnv(AtprotoSigningKeyKekDecryptOnly.ENV_VAR to "PrEvIoUsKeK0123456789abcdefghijklmnop+/="),
        )
    }

    @Test
    fun `production rejects a short decrypt-only signing-key KEK`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv(AtprotoSigningKeyKekDecryptOnly.ENV_VAR to "too-short"),
                )
            }
        assertTrue(failure.message!!.contains("${AtprotoSigningKeyKekDecryptOnly.ENV_VAR} must be at least 32"))
    }

    @Test
    fun `production rejects a decrypt-only signing-key KEK equal to the encrypting one`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv(AtprotoSigningKeyKekDecryptOnly.ENV_VAR to VALID_SIGNING_KEY_KEK),
                )
            }
        assertTrue(failure.message!!.contains("${AtprotoSigningKeyKekDecryptOnly.ENV_VAR} must differ"))
    }

    @Test
    fun `production rejects short JWT_SECRET`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv("JWT_SECRET" to "too-short"),
                )
            }
        assertTrue(failure.message!!.contains("at least 32"))
    }

    @Test
    fun `production rejects placeholder JWT_SECRET of correct length`() {
        val placeholder = "your-secret-key-change-in-production" // 36 chars, passes length check
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv("JWT_SECRET" to placeholder),
                )
            }
        assertTrue(failure.message!!.contains("known placeholder"))
    }

    @Test
    fun `production requires DATABASE_PASSWORD when DATABASE_URL has no credentials`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv =
                        productionEnv(
                            "DATABASE_URL" to "jdbc:postgresql://host:5432/logdate",
                            "DATABASE_PASSWORD" to "",
                        ),
                )
            }
        assertTrue(failure.message!!.contains("DATABASE_PASSWORD is required"))
    }

    @Test
    fun `production accepts DATABASE_URL with embedded credentials`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnv("DATABASE_URL" to "jdbc:postgresql://user:pass@host:5432/db"),
        )
    }

    @Test
    fun `production accepts a complete Cloud SQL connector contract`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv =
                productionEnv(
                    ValidationCase(
                        name = "Cloud SQL connector",
                        removed = setOf("DATABASE_URL"),
                        overrides =
                            mapOf(
                                "INSTANCE_CONNECTION_NAME" to "logdate:us-central1:logdate",
                                "DB_NAME" to "logdate",
                            ),
                        expectedMessage = "unused",
                    ),
                ),
        )
    }

    @Test
    fun `production rejects default DATABASE_PASSWORD`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv("DATABASE_PASSWORD" to "logdate"),
                )
            }
        assertTrue(failure.message!!.contains("known default"))
    }

    @Test
    fun `production accepts a fully-secured configuration`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnv(),
        )
    }

    @Test
    fun `production requires TRUSTED_PROXY_HOPS while forwarded headers are trusted`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnvWithout("TRUSTED_PROXY_HOPS"),
                )
            }
        assertTrue(failure.message!!.contains("TRUSTED_PROXY_HOPS is required"))
    }

    @Test
    fun `production rejects a TRUSTED_PROXY_HOPS that is not a small whole number`() {
        listOf("-1", "two", "5").forEach { value ->
            val failure =
                assertFailsWith<InsecureProductionConfigException>(value) {
                    ProductionConfigValidator.validate(
                        profile = RuntimeProfile.PRODUCTION,
                        readEnv = productionEnv("TRUSTED_PROXY_HOPS" to value),
                    )
                }
            assertTrue(failure.message!!.contains("TRUSTED_PROXY_HOPS must be a whole number"), value)
        }
    }

    @Test
    fun `production accepts each explicit TRUSTED_PROXY_HOPS and needs none without forwarded headers`() {
        listOf("0", "1").forEach { value ->
            ProductionConfigValidator.validate(profile = RuntimeProfile.PRODUCTION, readEnv = productionEnv("TRUSTED_PROXY_HOPS" to value))
        }
        val direct = VALID_PRODUCTION_ENV - "TRUSTED_PROXY_HOPS" + ("TRUST_FORWARDED_HEADERS" to "false")
        ProductionConfigValidator.validate(profile = RuntimeProfile.PRODUCTION, readEnv = { direct[it] })
    }

    @Test
    fun `reports every failure at once rather than bailing on first`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnvWithout(*VALID_PRODUCTION_ENV.keys.toTypedArray()),
                )
            }
        val message = failure.message!!
        assertTrue(message.contains("JWT_SECRET is required"))
        assertTrue(message.contains("DATABASE_PASSWORD is required"))
        assertTrue(message.contains("WEBAUTHN_RP_ID is required"))
        assertTrue(message.contains("WEBAUTHN_ORIGIN is required"))
    }

    @Test
    fun `production requires WEBAUTHN_RP_ID`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnvWithout("WEBAUTHN_RP_ID"),
                )
            }
        assertTrue(failure.message!!.contains("WEBAUTHN_RP_ID is required"))
    }

    @Test
    fun `production requires WEBAUTHN_ORIGIN`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnvWithout("WEBAUTHN_ORIGIN"),
                )
            }
        assertTrue(failure.message!!.contains("WEBAUTHN_ORIGIN is required"))
    }

    @Test
    fun `production rejects http WEBAUTHN_ORIGIN`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv("WEBAUTHN_ORIGIN" to "http://cloud.logdate.app"),
                )
            }
        assertTrue(failure.message!!.contains("https://"))
    }

    @Test
    fun `production rejects RP ID that is not the apex of origin`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv("WEBAUTHN_ORIGIN" to "https://other-domain.example"),
                )
            }
        assertTrue(failure.message!!.contains("registrable apex"))
    }

    @Test
    fun `production accepts RP ID equal to origin host (staging pattern)`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv =
                productionEnv(
                    "WEBAUTHN_RP_ID" to "cloud-staging.logdate.app",
                    "WEBAUTHN_ORIGIN" to "https://cloud-staging.logdate.app",
                ),
        )
    }

    @Test
    fun `production accepts RP ID as parent suffix of origin host (production pattern)`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnv(),
        )
    }

    @Test
    fun `production accepts android apk-key-hash entries in WEBAUTHN_ALLOWED_ORIGINS`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnv(),
        )
    }

    @Test
    fun `production rejects a malformed WEBAUTHN_ALLOWED_ORIGINS entry`() {
        val failure =
            assertFailsWith<InsecureProductionConfigException> {
                ProductionConfigValidator.validate(
                    profile = RuntimeProfile.PRODUCTION,
                    readEnv = productionEnv("WEBAUTHN_ALLOWED_ORIGINS" to "ftp://nope.example"),
                )
            }
        assertTrue(failure.message!!.contains("WEBAUTHN_ALLOWED_ORIGINS"))
    }

    @Test
    fun `production still boots web-only when WEBAUTHN_ALLOWED_ORIGINS is unset`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnvWithout("WEBAUTHN_ALLOWED_ORIGINS"),
        )
    }

    @Test
    fun `production keeps secure self-hosting valid without managed billing`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv =
                productionEnv(
                    ValidationCase(
                        name = "self-hosted filesystem storage",
                        removed = setOf("GCS_BUCKET_NAME"),
                        overrides =
                            mapOf(
                                "LOGDATE_BLOB_STORAGE_DIR" to "/var/lib/logdate/blobs",
                                "BILLING_PROVIDER" to "disabled",
                            ),
                        expectedMessage = "unused",
                    ),
                ),
        )
    }

    @Test
    fun `production accepts a complete first-party deployment`() {
        ProductionConfigValidator.validate(
            profile = RuntimeProfile.PRODUCTION,
            readEnv = productionEnv("LOGDATE_EXPECT_FIRST_PARTY" to "true", "LOGDATE_DEPLOYMENT_KIND" to "first_party"),
        )
    }

    @Test
    fun `production rejects every missing or unsafe managed deployment setting`() {
        val cases =
            listOf(
                ValidationCase(
                    name = "database connection is absent",
                    removed = setOf("DATABASE_URL"),
                    expectedMessage = "DATABASE_URL or INSTANCE_CONNECTION_NAME and DB_NAME",
                ),
                ValidationCase(
                    name = "durable blob storage is absent",
                    removed = setOf("GCS_BUCKET_NAME"),
                    expectedMessage = "GCS_BUCKET_NAME or LOGDATE_BLOB_STORAGE_DIR",
                ),
                ValidationCase(
                    name = "first-party expectation has self-hosted descriptor",
                    overrides = mapOf("LOGDATE_EXPECT_FIRST_PARTY" to "true", "LOGDATE_DEPLOYMENT_KIND" to "self_hosted"),
                    expectedMessage = "LOGDATE_DEPLOYMENT_KIND must be first_party",
                ),
                ValidationCase(
                    name = "first-party expectation rejects hyphenated descriptor alias",
                    overrides = mapOf("LOGDATE_EXPECT_FIRST_PARTY" to "true", "LOGDATE_DEPLOYMENT_KIND" to "first-party"),
                    expectedMessage = "LOGDATE_DEPLOYMENT_KIND must be first_party",
                ),
                ValidationCase(
                    name = "first-party expectation rejects concatenated descriptor alias",
                    overrides = mapOf("LOGDATE_EXPECT_FIRST_PARTY" to "true", "LOGDATE_DEPLOYMENT_KIND" to "firstparty"),
                    expectedMessage = "LOGDATE_DEPLOYMENT_KIND must be first_party",
                ),
                ValidationCase(
                    name = "first-party deployment requires GCS instead of a filesystem path",
                    removed = setOf("GCS_BUCKET_NAME"),
                    overrides =
                        mapOf(
                            "LOGDATE_EXPECT_FIRST_PARTY" to "true",
                            "LOGDATE_DEPLOYMENT_KIND" to "first_party",
                            "LOGDATE_BLOB_STORAGE_DIR" to "/tmp/blobs",
                        ),
                    expectedMessage = "GCS_BUCKET_NAME is required when LOGDATE_EXPECT_FIRST_PARTY=true",
                ),
                ValidationCase(
                    name = "public origin is absent",
                    removed = setOf("LOGDATE_PUBLIC_ORIGIN"),
                    expectedMessage = "LOGDATE_PUBLIC_ORIGIN must be an https:// URL",
                ),
                ValidationCase(
                    name = "public origin is insecure",
                    overrides = mapOf("LOGDATE_PUBLIC_ORIGIN" to "http://cloud.logdate.app"),
                    expectedMessage = "LOGDATE_PUBLIC_ORIGIN must be an https:// URL",
                ),
                ValidationCase(
                    name = "PDS endpoint does not match public origin",
                    overrides = mapOf("ATPROTO_PDS_SERVICE_URL" to "https://other.logdate.app"),
                    expectedMessage = "ATPROTO_PDS_SERVICE_URL must equal LOGDATE_PUBLIC_ORIGIN",
                ),
                ValidationCase(
                    name = "first-party billing is disabled",
                    overrides =
                        mapOf(
                            "LOGDATE_EXPECT_FIRST_PARTY" to "true",
                            "LOGDATE_DEPLOYMENT_KIND" to "first_party",
                            "BILLING_PROVIDER" to "disabled",
                        ),
                    expectedMessage = "BILLING_PROVIDER must not be disabled",
                ),
                ValidationCase(
                    name = "health token is absent",
                    removed = setOf("HEALTH_INTERNAL_TOKEN"),
                    expectedMessage = "HEALTH_INTERNAL_TOKEN is required",
                ),
                ValidationCase(
                    name = "release version is absent",
                    removed = setOf("RELEASE_VERSION"),
                    expectedMessage = "RELEASE_VERSION must match",
                ),
                ValidationCase(
                    name = "release version is not immutable SHA identity",
                    overrides = mapOf("RELEASE_VERSION" to "logdate-server@short-sha"),
                    expectedMessage = "RELEASE_VERSION must match",
                ),
                ValidationCase(
                    name = "server encryption is disabled",
                    overrides = mapOf("SERVER_ENCRYPTION_ENABLED" to "false"),
                    expectedMessage = "SERVER_ENCRYPTION_ENABLED must be true",
                ),
                ValidationCase(
                    name = "server encryption key is absent",
                    removed = setOf("SERVER_ENCRYPTION_KEY"),
                    expectedMessage = "SERVER_ENCRYPTION_KEY is required",
                ),
                ValidationCase(
                    name = "server encryption key is not base64",
                    overrides = mapOf("SERVER_ENCRYPTION_KEY" to "not base64"),
                    expectedMessage = "SERVER_ENCRYPTION_KEY must be valid base64",
                ),
                ValidationCase(
                    name = "server encryption key has invalid AES length",
                    overrides = mapOf("SERVER_ENCRYPTION_KEY" to "MTIzNDU2Nw=="),
                    expectedMessage = "SERVER_ENCRYPTION_KEY must decode to 16, 24, or 32 bytes",
                ),
                ValidationCase(
                    name = "server encryption key ID is absent",
                    removed = setOf("SERVER_ENCRYPTION_KEY_ID"),
                    expectedMessage = "SERVER_ENCRYPTION_KEY_ID is required",
                ),
                ValidationCase(
                    name = "media URLs are unsigned",
                    overrides = mapOf("SYNC_MEDIA_SIGNED_URLS" to "false"),
                    expectedMessage = "SYNC_MEDIA_SIGNED_URLS must be true",
                ),
                ValidationCase(
                    name = "first-party passkeys lack Android origin",
                    overrides =
                        mapOf(
                            "LOGDATE_EXPECT_FIRST_PARTY" to "true",
                            "LOGDATE_DEPLOYMENT_KIND" to "first_party",
                            "WEBAUTHN_ALLOWED_ORIGINS" to "https://cloud.logdate.app",
                        ),
                    expectedMessage = "WEBAUTHN_ALLOWED_ORIGINS must include a valid android:apk-key-hash origin",
                ),
                ValidationCase(
                    name = "first-party certificate fingerprint is absent",
                    overrides = mapOf("LOGDATE_EXPECT_FIRST_PARTY" to "true", "LOGDATE_DEPLOYMENT_KIND" to "first_party"),
                    removed = setOf("ANDROID_CERT_FINGERPRINTS"),
                    expectedMessage = "ANDROID_CERT_FINGERPRINTS must include colon-hex SHA-256 fingerprints",
                ),
                ValidationCase(
                    name = "first-party certificate fingerprint is malformed",
                    overrides =
                        mapOf(
                            "LOGDATE_EXPECT_FIRST_PARTY" to "true",
                            "LOGDATE_DEPLOYMENT_KIND" to "first_party",
                            "ANDROID_CERT_FINGERPRINTS" to "not-a-fingerprint",
                        ),
                    expectedMessage = "ANDROID_CERT_FINGERPRINTS must include colon-hex SHA-256 fingerprints",
                ),
            )

        cases.forEach { case ->
            val failure =
                assertFailsWith<InsecureProductionConfigException>(case.name) {
                    ProductionConfigValidator.validate(
                        profile = RuntimeProfile.PRODUCTION,
                        readEnv = productionEnv(case),
                    )
                }
            assertTrue(failure.message!!.contains(case.expectedMessage), case.name)
        }
    }

    private fun productionEnv(case: ValidationCase): (String) -> String? {
        val map = (VALID_PRODUCTION_ENV + case.overrides) - case.removed
        return { name -> map[name] }
    }

    private fun productionEnv(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = VALID_PRODUCTION_ENV + pairs
        return { name -> map[name] }
    }

    private fun productionEnvWithout(vararg names: String): (String) -> String? {
        val map = VALID_PRODUCTION_ENV - names.toSet()
        return { name -> map[name] }
    }

    private data class ValidationCase(
        val name: String,
        val removed: Set<String> = emptySet(),
        val overrides: Map<String, String> = emptyMap(),
        val expectedMessage: String,
    )

    companion object {
        private const val VALID_JWT_SECRET = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789+/=abc"
        private const val VALID_SIGNING_KEY_KEK = "ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210+/=xyz"
        private const val VALID_SESSION_SECRET = "SeSsIoNsEcReT0123456789abcdefghijklmnop+/="
        private const val VALID_DB_PASSWORD = "a-real-secret-password"
        private const val VALID_RELEASE = "logdate-server@0123456789abcdef0123456789abcdef01234567"
        private const val VALID_ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZg=="
        private const val VALID_ANDROID_ORIGIN = "android:apk-key-hash:pNiP8Z6X1xH6vQX0r1Tq8m9Hb3kq9b0c0d1e2f3g4h5"
        private const val VALID_ANDROID_FINGERPRINT =
            "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        private val VALID_PRODUCTION_ENV =
            mapOf(
                "JWT_SECRET" to VALID_JWT_SECRET,
                "ATPROTO_SIGNING_KEY_KEK" to VALID_SIGNING_KEY_KEK,
                "ATPROTO_SESSION_SECRET" to VALID_SESSION_SECRET,
                "DATABASE_PASSWORD" to VALID_DB_PASSWORD,
                "DATABASE_URL" to "jdbc:postgresql://user:pass@host:5432/logdate",
                "GCS_BUCKET_NAME" to "logdate-media-production",
                "LOGDATE_PUBLIC_ORIGIN" to "https://cloud.logdate.app",
                "ATPROTO_PDS_SERVICE_URL" to "https://cloud.logdate.app",
                "BILLING_PROVIDER" to "stripe",
                "HEALTH_INTERNAL_TOKEN" to "health-token",
                "TRUSTED_PROXY_HOPS" to "0",
                "RELEASE_VERSION" to VALID_RELEASE,
                "SERVER_ENCRYPTION_ENABLED" to "true",
                "SERVER_ENCRYPTION_KEY" to VALID_ENCRYPTION_KEY,
                "SERVER_ENCRYPTION_KEY_ID" to "key-2026-08",
                "SYNC_MEDIA_SIGNED_URLS" to "true",
                "WEBAUTHN_RP_ID" to "logdate.app",
                "WEBAUTHN_ORIGIN" to "https://cloud.logdate.app",
                "WEBAUTHN_ALLOWED_ORIGINS" to "https://cloud.logdate.app,$VALID_ANDROID_ORIGIN",
                "ANDROID_CERT_FINGERPRINTS" to VALID_ANDROID_FINGERPRINT,
            )
    }
}
