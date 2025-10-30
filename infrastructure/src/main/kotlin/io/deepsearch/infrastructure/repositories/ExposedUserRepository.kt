package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.infrastructure.database.UserTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedUserRepository : IUserRepository {

    override suspend fun save(user: User): User = suspendTransaction {
        val id = UserTable.insert {
            it[email] = user.email.value
            it[passwordHash] = user.passwordHash?.value
            it[oauthProvider] = user.oauthProvider?.name
            it[oauthProviderId] = user.oauthProviderId
            it[createdAtEpochMs] = user.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = user.updatedAt.toEpochMilliseconds()
            it[version] = user.version
        }[UserTable.id]
        
        user.id = UserId(id)
        user
    }

    override suspend fun findById(id: UserId): User? = suspendTransaction {
        UserTable.selectAll()
            .where { UserTable.id eq id.value }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findByEmail(email: Email): User? = suspendTransaction {
        UserTable.selectAll()
            .where { UserTable.email eq email.value }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findByOAuthProvider(provider: OAuthProvider, providerId: String): User? = suspendTransaction {
        UserTable.selectAll()
            .where { (UserTable.oauthProvider eq provider.name) and (UserTable.oauthProviderId eq providerId) }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<User> = suspendTransaction {
        UserTable.selectAll()
            .map { mapRowToUser(it) }
            .toList()
    }

    override suspend fun update(user: User): User = suspendTransaction {
        val affectedRows = UserTable.update({ 
            (UserTable.id eq user.id!!.value) and (UserTable.version eq user.version) 
        }) {
            it[email] = user.email.value
            it[passwordHash] = user.passwordHash?.value
            it[oauthProvider] = user.oauthProvider?.name
            it[oauthProviderId] = user.oauthProviderId
            it[updatedAtEpochMs] = user.updatedAt.toEpochMilliseconds()
            it[version] = user.version + 1
        }
        
        if (affectedRows == 0) {
            throw OptimisticLockException("User", user.id!!.value, user.version)
        }
        
        user.version += 1
        user
    }

    override suspend fun delete(id: UserId): Boolean = suspendTransaction {
        UserTable.deleteWhere { UserTable.id eq id.value } > 0
    }

    override suspend fun exists(id: UserId): Boolean = suspendTransaction {
        UserTable.selectAll()
            .where { UserTable.id eq id.value }
            .limit(1)
            .count() > 0
    }

    private fun mapRowToUser(row: ResultRow): User {
        return User(
            id = UserId(row[UserTable.id]),
            email = Email(row[UserTable.email]),
            passwordHash = row[UserTable.passwordHash]?.let { PasswordHash(it) },
            oauthProvider = row[UserTable.oauthProvider]?.let { OAuthProvider.fromString(it) },
            oauthProviderId = row[UserTable.oauthProviderId],
            createdAt = Instant.fromEpochMilliseconds(row[UserTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[UserTable.updatedAtEpochMs]),
            version = row[UserTable.version]
        )
    }
} 