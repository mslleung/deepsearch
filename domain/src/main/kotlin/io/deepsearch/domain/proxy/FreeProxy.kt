package io.deepsearch.domain.proxy

/**
 * Represents a free proxy server from ProxyScrape.
 *
 * @property protocol The proxy protocol (http, https, socks4, socks5)
 * @property host The proxy server hostname or IP address
 * @property port The proxy server port
 * @property country The ISO country code of the proxy (e.g., "US", "DE", "JP")
 */
data class FreeProxy(
    val protocol: String,
    val host: String,
    val port: Int,
    val country: String
) {
    /**
     * Returns the proxy URL in standard format: protocol://host:port
     */
    val url: String get() = "$protocol://$host:$port"

    companion object {
        /**
         * Parse a proxy string in format "protocol://host:port"
         * Falls back to http if no protocol specified.
         *
         * @param proxyString The proxy string to parse
         * @param country The ISO country code to assign to this proxy
         * @return FreeProxy instance or null if parsing fails
         */
        fun parse(proxyString: String, country: String = "unknown"): FreeProxy? {
            return try {
                val normalized = proxyString.trim()
                if (normalized.isBlank()) return null

                // Check if protocol is specified
                val (protocol, hostPort) = if (normalized.contains("://")) {
                    val parts = normalized.split("://", limit = 2)
                    parts[0].lowercase() to parts[1]
                } else {
                    "http" to normalized
                }

                // Parse host:port
                val colonIndex = hostPort.lastIndexOf(':')
                if (colonIndex <= 0) return null

                val host = hostPort.substring(0, colonIndex)
                val port = hostPort.substring(colonIndex + 1).toIntOrNull() ?: return null

                if (host.isBlank() || port <= 0 || port > 65535) return null

                FreeProxy(protocol, host, port, country)
            } catch (e: Exception) {
                null
            }
        }
    }
}

