package app.logdate.server.di

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentityAdoptionOriginTest {
    @Test fun configuredDescriptorOriginStaysExactWithoutAcceptingAPdsPath() {
        assertEquals("https://cloud.example:8443", identityAdoptionBackendOrigin("https://cloud.example:8443/"))
        val invalidOrigins =
            listOf(
                "https://cloud.example/pds",
                "https://cloud.example/?query=1",
                "https://cloud.example/#part",
                "https://user@cloud.example",
                "http://cloud.example",
                "https://cloud.example//",
                "https://CLOUD.example",
                "https://cloud.example:443",
            )
        for (invalid in invalidOrigins) {
            assertFailsWith<IllegalArgumentException> { identityAdoptionBackendOrigin(invalid) }
        }
    }
}
