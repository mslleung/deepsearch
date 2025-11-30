package io.deepsearch.domain.config

/**
 * Configuration for PostgreSQL database connection.
 * 
 * @property host The PostgreSQL server hostname
 * @property port The PostgreSQL server port
 * @property database The database name to connect to
 * @property username The username for database authentication
 * @property password The password for database authentication
 * @property poolInitialSize Initial number of connections in the pool
 * @property poolMaxSize Maximum number of connections in the pool
 * @property poolMaxIdleTimeMinutes Close idle connections after this many minutes
 */
data class PostgresConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val poolInitialSize: Int = 5,
    val poolMaxSize: Int = 20,
    val poolMaxIdleTimeMinutes: Long = 30,
) {
    init {
        require(host.isNotBlank()) { "PostgreSQL host cannot be blank" }
        require(port > 0) { "PostgreSQL port must be positive" }
        require(database.isNotBlank()) { "PostgreSQL database name cannot be blank" }
        require(username.isNotBlank()) { "PostgreSQL username cannot be blank" }
        require(password.isNotBlank()) { "PostgreSQL password cannot be blank" }
        require(poolInitialSize > 0) { "Pool initial size must be positive" }
        require(poolMaxSize >= poolInitialSize) { "Pool max size must be >= initial size" }
        require(poolMaxIdleTimeMinutes > 0) { "Pool max idle time must be positive" }
    }
}

