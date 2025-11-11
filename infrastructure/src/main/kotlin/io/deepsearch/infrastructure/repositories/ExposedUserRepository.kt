package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.OAuthProvider
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.infrastructure.database.UserTable
import io.deepsearch.infrastructure.services.ITransactionService
import io.deepsearch.infrastructure.services.TransactionService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ExposedUserRepository(
    private val userTable: UserTable,
    private val transactionService: ITransactionService
) : IUserRepository {

    override suspend fun save(user: User): User = transactionService.withTransaction {
        val id = userTable.insert {
            it[email] = user.email.value
            it[passwordHash] = user.passwordHash?.value
            it[oauthProvider] = user.oauthProvider?.name
            it[oauthProviderId] = user.oauthProviderId
            it[createdAtEpochMs] = user.createdAt.toEpochMilliseconds()
            it[updatedAtEpochMs] = user.updatedAt.toEpochMilliseconds()
            it[version] = user.version
        }[userTable.id]
        
        user.id = UserId(id)
        user
    }

    override suspend fun findById(id: UserId): User? = transactionService.withTransaction {
        userTable.selectAll()
            .where { userTable.id eq id.value }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findByEmail(email: Email): User? = transactionService.withTransaction {
        userTable.selectAll()
            .where { userTable.email eq email.value }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findByOAuthProvider(provider: OAuthProvider, providerId: String): User? = transactionService.withTransaction {
        userTable.selectAll()
            .where { (userTable.oauthProvider eq provider.name) and (userTable.oauthProviderId eq providerId) }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<User> = transactionService.withTransaction {
        userTable.selectAll()
            .map { mapRowToUser(it) }
            .toList()
    }

    override suspend fun update(user: User): User = transactionService.withTransaction {
        val affectedRows = userTable.update({ 
            (userTable.id eq user.id!!.value) and (userTable.version eq user.version) 
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

    override suspend fun delete(id: UserId): Boolean = transactionService.withTransaction {
        userTable.deleteWhere { userTable.id eq id.value } > 0
    }

    override suspend fun exists(id: UserId): Boolean = transactionService.withTransaction {
        userTable.selectAll()
            .where { userTable.id eq id.value }
            .limit(1)
            .count() > 0
    }

    private fun mapRowToUser(row: ResultRow): User {
        return User(
            id = UserId(row[userTable.id]),
            email = Email(row[userTable.email]),
            passwordHash = row[userTable.passwordHash]?.let { PasswordHash(it) },
            oauthProvider = row[userTable.oauthProvider]?.let { OAuthProvider.fromString(it) },
            oauthProviderId = row[userTable.oauthProviderId],
            createdAt = Instant.fromEpochMilliseconds(row[userTable.createdAtEpochMs]),
            updatedAt = Instant.fromEpochMilliseconds(row[userTable.updatedAtEpochMs]),
            version = row[userTable.version]
        )
    }
} 