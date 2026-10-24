package app.logdate.server.routes

import app.logdate.server.oauth.OAuthPasskeyPage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

private val pageStyles: String by lazy { serverResource("oauth/authorize.css") }
private val pageScript: String by lazy { serverResource("oauth/authorize.js") }
private val nonceRandom = SecureRandom()

/**
 * Answers with the browser authorization page for [page]: passkey sign-in when the request is
 * valid, otherwise an explanation of what went wrong and what to do next.
 *
 * Every response carries a fresh nonce for its inline style and script, cannot be framed by
 * another site, and is never cached.
 */
internal suspend fun ApplicationCall.respondAuthorizationPage(page: OAuthPasskeyPage) {
    val nonce = ByteArray(NONCE_BYTES).also(nonceRandom::nextBytes).let { Base64.getEncoder().encodeToString(it) }
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; connect-src 'self'; " +
            "base-uri 'none'; frame-ancestors 'none'",
    )
    response.header("X-Frame-Options", "DENY")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header(HttpHeaders.CacheControl, "no-store")
    val status =
        when (page) {
            is OAuthPasskeyPage.SignIn -> HttpStatusCode.OK
            OAuthPasskeyPage.ServerError -> HttpStatusCode.ServiceUnavailable
            else -> HttpStatusCode.BadRequest
        }
    respondText(renderAuthorizationPage(page, nonce), ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

internal fun renderAuthorizationPage(
    page: OAuthPasskeyPage,
    nonce: String,
): String =
    when (page) {
        is OAuthPasskeyPage.SignIn -> {
            signInPage(page, nonce)
        }

        OAuthPasskeyPage.RequestExpired -> {
            messagePage(
                nonce = nonce,
                title = "This sign-in link has expired",
                body =
                    "Sign-in links only last a few minutes, and each one works once. Go back to the app you were " +
                        "signing in to and start signing in again.",
            )
        }

        OAuthPasskeyPage.ServerError -> {
            messagePage(
                nonce = nonce,
                title = "Sign-in isn't available right now",
                body =
                    "The LogDate server couldn't load this sign-in request. Try again in a few minutes from the app you " +
                        "were signing in to.",
            )
        }

        OAuthPasskeyPage.ClientMismatch -> {
            messagePage(
                nonce = nonce,
                title = "This sign-in link doesn't match its app",
                body = "The link was changed after the app created it. Go back to the app and start signing in again.",
            )
        }

        is OAuthPasskeyPage.UnknownAccount -> {
            messagePage(
                nonce = nonce,
                title = "No LogDate account for ${page.requestedAccount}",
                body =
                    "${page.clientLabel} asked to sign in as ${page.requestedAccount}, which isn't a LogDate account. Go back " +
                        "to the app and sign in with your LogDate handle.",
            )
        }
    }

private fun signInPage(
    page: OAuthPasskeyPage.SignIn,
    nonce: String,
): String {
    val client = page.clientLabel.escapeHtml()
    val account = page.accountHandle?.let { "@$it".escapeHtml() }
    val accountPhrase = account?.let { ", <strong>$it</strong>" }.orEmpty()
    // An untrusted app is already named by its client ID URL, so its address would only repeat it.
    val address =
        page.clientAddress
            .takeUnless { page.clientLabel.contains(it) }
            ?.let { " <span class=\"address\">${it.escapeHtml()}</span>" }
            .orEmpty()
    // No scope opens anything beyond the identity yet: no collection is open to apps and uploads
    // are refused, so every request gets the same, accurate sentence.
    val access = "If you continue, this app learns which LogDate account you are. It can't see or change anything in your account."
    val pageData =
        buildJsonObject {
            put("request_uri", page.requestUri)
            put("client_label", page.clientLabel)
        }.toString().escapeForScript()
    val content =
        listOf(
            "<h1>Sign in to $client</h1>",
            "<p class=\"lead\"><strong>$client</strong>$address wants to confirm your LogDate identity$accountPhrase.</p>",
            "<p>$access</p>",
            "<div class=\"actions\">",
            "<button type=\"button\" id=\"continue\" class=\"primary\">Continue with passkey</button>",
            "<form method=\"post\" action=\"/oauth/authorize\">",
            "<input type=\"hidden\" name=\"request_uri\" value=\"${page.requestUri.escapeHtml()}\">",
            "<input type=\"hidden\" name=\"decision\" value=\"deny\">",
            "<button type=\"submit\" class=\"text\">Cancel</button>",
            "</form>",
            "</div>",
            "<p id=\"status\" class=\"status\" role=\"status\" aria-live=\"polite\"></p>",
            "<noscript><p class=\"status\" data-kind=\"error\">Turn on JavaScript in this browser to sign in with a passkey.</p></noscript>",
        ).joinToString("\n")
    val footer = "<p class=\"footnote\">Only continue if you started signing in to $client yourself.</p>"
    val scripts =
        "<script type=\"application/json\" id=\"authorization-request\">$pageData</script>\n" +
            "<script nonce=\"$nonce\">\n$pageScript</script>"
    return document(title = "Sign in to $client", nonce = nonce, content = content, footer = footer, scripts = scripts)
}

private fun messagePage(
    nonce: String,
    title: String,
    body: String,
): String =
    document(
        title = title.escapeHtml(),
        nonce = nonce,
        content = "<h1>${title.escapeHtml()}</h1>\n<p>${body.escapeHtml()}</p>",
        footer = "",
        scripts = "",
    )

private fun document(
    title: String,
    nonce: String,
    content: String,
    footer: String,
    scripts: String,
): String =
    listOf(
        "<!DOCTYPE html>",
        "<html lang=\"en\">",
        "<head>",
        "<meta charset=\"utf-8\">",
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
        "<meta name=\"robots\" content=\"noindex\">",
        "<title>$title · LogDate</title>",
        "<style nonce=\"$nonce\">$pageStyles</style>",
        "</head>",
        "<body>",
        "<div class=\"page\">",
        "<main class=\"card\">",
        "<p class=\"brand\">LogDate</p>",
        content,
        "</main>",
        footer,
        "</div>",
        scripts,
        "</body>",
        "</html>",
    ).filter(String::isNotEmpty).joinToString("\n")

private fun String.escapeHtml(): String =
    buildString(length) {
        this@escapeHtml.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

/** JSON inside a `<script>` element must not be able to close the element or open a comment. */
private fun String.escapeForScript(): String = replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")

private fun serverResource(path: String): String =
    requireNotNull(Application::class.java.classLoader.getResource(path)) { "$path missing from server resources" }.readText()

private const val NONCE_BYTES = 16
