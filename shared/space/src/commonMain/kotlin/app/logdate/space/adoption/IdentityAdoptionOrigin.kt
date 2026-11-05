package app.logdate.space.adoption

import io.ktor.http.Url
import kotlin.coroutines.cancellation.CancellationException

internal fun identityAdoptionOrigin(value: String): String {
    try {
        originCheck(value.substringBefore("://").equals("https", ignoreCase = true))
        originCheck('?' !in value && '#' !in value)
        val url = Url(value)
        originCheck(url.protocol.name.equals("https", ignoreCase = true) && url.host.isNotEmpty())
        originCheck(url.user == null && url.password == null)
        originCheck(url.encodedPath.isEmpty() || url.encodedPath == "/")
        val authority = value.substringAfter("://").substringBefore('/')
        val explicitPort = explicitPort(authority)
        originCheck(url.port in 1..65535)
        val host = url.host.lowercase().let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
        val port = explicitPort ?: url.port
        return "https://$host" + if (port == 443) "" else ":$port"
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_BINDING)
    }
}

private fun explicitPort(authority: String): Int? {
    val suffix = if (authority.startsWith('[')) authority.substringAfter(']', "") else authority.substringAfterLast(':', "")
    if (authority.startsWith('[')) {
        if (suffix.isEmpty()) return null
        originCheck(suffix.startsWith(':'))
        return port(suffix.drop(1))
    }
    return if (':' in authority) port(suffix) else null
}

private fun port(text: String): Int {
    originCheck(text.isNotEmpty() && text.all { it in '0'..'9' })
    val port = text.toIntOrNull() ?: throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_BINDING)
    originCheck(port in 1..65535)
    return port
}

private fun originCheck(valid: Boolean) {
    if (!valid) throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_BINDING)
}
