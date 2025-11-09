package io.deepsearch.domain.config

/**
 * Configuration for PostgreSQL database connection.
 * 
 * @property host The PostgreSQL server hostname
 * @property port The PostgreSQL server port
 * @property database The database name to connect to
 * @property username The username for database authentication
 * @property password The password for database authentication
 */
data class PostgresConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String
) {
    init {
        require(host.isNotBlank()) { "PostgreSQL host cannot be blank" }
        require(port > 0) { "PostgreSQL port must be positive" }
        require(database.isNotBlank()) { "PostgreSQL database name cannot be blank" }
        require(username.isNotBlank()) { "PostgreSQL username cannot be blank" }
        require(password.isNotBlank()) { "PostgreSQL password cannot be blank" }
    }
}

