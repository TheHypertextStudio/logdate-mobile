package studio.hypertext.atproto.oauth.client

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import javax.net.SocketFactory

/**
 * HTTP clients for the OAuth client on the JVM.
 */
public object JvmOAuthHttp {
    /**
     * Returns an OkHttp-backed client that only connects to public internet addresses.
     *
     * Signing someone in means fetching URLs that strangers control: their handle's well-known
     * file, their DID document's data server, and that server's metadata. Without a filter, a
     * handle or DID document could point the client at `localhost`, a cloud metadata service, or
     * another host on the private network (server-side request forgery).
     *
     * This client checks the address of every connection after DNS resolution, at the moment the
     * socket connects, so a name that resolves to a public address once and a private one later
     * (DNS rebinding) is still refused. It also connects directly, ignoring any configured proxy,
     * because a proxy would resolve names where the filter cannot see them.
     *
     * Use the same client for the identity resolver and for [AtprotoOAuthClient].
     *
     * @param configure Further client configuration, such as an engine timeout. The proxy and
     *   socket factory are set after it runs, so it cannot turn the filter off. Do not add logging
     *   interceptors to the engine: the OAuth client uses the engine for token requests.
     */
    public fun publicInternetClient(configure: HttpClientConfig<OkHttpConfig>.() -> Unit = {}): HttpClient =
        HttpClient(OkHttp) {
            configure()
            engine {
                // Ktor chains `config` blocks in order, so this one runs after any the caller added.
                proxy = Proxy.NO_PROXY
                config {
                    proxy(Proxy.NO_PROXY)
                    socketFactory(PublicAddressSocketFactory)
                }
            }
        }

    /**
     * Whether [address] is a public unicast address the client may connect to.
     *
     * Refused: unspecified, loopback, link-local, private (RFC 1918 and IPv6 unique local),
     * shared address space (RFC 6598), multicast, documentation, benchmarking, discard-only, and
     * reserved ranges, the 6to4 relay anycast block, local-use NAT64 (`64:ff9b:1::/48`), Teredo
     * tunnels, and IPv6 forms that embed a refused IPv4 address (IPv4-mapped, IPv4-translated,
     * IPv4-compatible, well-known NAT64, and 6to4).
     */
    public fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> isPublicIpv4(bytes)
            is Inet6Address -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(b: List<Int>): Boolean =
        when {
            b[0] == 0 -> false

            // 0.0.0.0/8 "this network"
            b[0] == 10 -> false

            // 10.0.0.0/8
            b[0] == 100 && b[1] in 64..127 -> false

            // 100.64.0.0/10 shared address space
            b[0] == 127 -> false

            // 127.0.0.0/8 loopback
            b[0] == 169 && b[1] == 254 -> false

            // 169.254.0.0/16 link-local, cloud metadata
            b[0] == 172 && b[1] in 16..31 -> false

            // 172.16.0.0/12
            b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2) -> false

            // 192.0.0.0/24, 192.0.2.0/24
            b[0] == 192 && b[1] == 88 && b[2] == 99 -> false

            // 192.88.99.0/24 6to4 relay anycast
            b[0] == 192 && b[1] == 168 -> false

            // 192.168.0.0/16
            b[0] == 198 && b[1] in 18..19 -> false

            // 198.18.0.0/15 benchmarking
            b[0] == 198 && b[1] == 51 && b[2] == 100 -> false

            // 198.51.100.0/24 documentation
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> false

            // 203.0.113.0/24 documentation
            b[0] >= 224 -> false

            // multicast, reserved, broadcast
            else -> true
        }

    private fun isPublicIpv6(b: List<Int>): Boolean {
        fun zero(
            from: Int,
            to: Int,
        ) = b.subList(from, to).all { it == 0 }
        val embeddedIpv4 = b.subList(12, 16)
        return when {
            (b[0] and 0xfe) == 0xfc -> false

            // fc00::/7 unique local
            b[0] == 0xfe && (b[1] and 0xc0) == 0x80 -> false

            // fe80::/10 link-local
            b[0] == 0xfe && (b[1] and 0xc0) == 0xc0 -> false

            // fec0::/10 site-local
            b[0] == 0xff -> false

            // multicast
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8 -> false

            // 2001:db8::/32 documentation
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0 -> false

            // 2001::/32 Teredo
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0x02 && b[4] == 0 && b[5] == 0 -> false

            // 2001:2::/48 benchmarking
            b[0] == 0x01 && b[1] == 0 && zero(2, 8) -> false

            // 100::/64 discard-only
            b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && b[4] == 0 && b[5] == 0x01 -> false

            // 64:ff9b:1::/48 local NAT64
            // ::ffff:a.b.c.d (IPv4-mapped) and ::a.b.c.d (IPv4-compatible)
            zero(0, 10) && ((b[10] == 0xff && b[11] == 0xff) || (b[10] == 0 && b[11] == 0)) -> isPublicIpv4(embeddedIpv4)

            // ::ffff:0:a.b.c.d (IPv4-translated, RFC 6052 / SIIT)
            zero(0, 8) && b[8] == 0xff && b[9] == 0xff && b[10] == 0 && b[11] == 0 -> isPublicIpv4(embeddedIpv4)

            // 64:ff9b::/96 well-known NAT64
            b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && zero(4, 12) -> isPublicIpv4(embeddedIpv4)

            b[0] == 0x20 && b[1] == 0x02 -> isPublicIpv4(b.subList(2, 6))

            // 2002::/16 6to4
            else -> true
        }
    }
}

/**
 * Creates sockets that refuse to connect to non-public addresses.
 */
private object PublicAddressSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = PublicAddressSocket()

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = PublicAddressSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket =
        PublicAddressSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(
        host: InetAddress,
        port: Int,
    ): Socket = PublicAddressSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        PublicAddressSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

private class PublicAddressSocket : Socket() {
    override fun connect(
        endpoint: SocketAddress,
        timeout: Int,
    ) {
        val address =
            (endpoint as? InetSocketAddress)?.address
                ?: throw SocketException("Refusing to connect to unresolved address $endpoint")
        if (!JvmOAuthHttp.isPublicAddress(address)) {
            throw SocketException("Refusing to connect to non-public address ${address.hostAddress}")
        }
        super.connect(endpoint, timeout)
    }
}
