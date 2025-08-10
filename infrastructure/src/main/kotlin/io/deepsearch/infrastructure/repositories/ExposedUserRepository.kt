package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserName
import io.deepsearch.domain.models.valueobjects.UserAge
import io.deepsearch.infrastructure.database.UserTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

class ExposedUserRepository : IUserRepository {
    
    override suspend fun save(user: User): User = newSuspendedTransaction(Dispatchers.IO) {
        val id = UserTable.insert {
            it[name] = user.name.value
            it[age] = user.age.value
        }[UserTable.id]
        
        user.withId(UserId(id))
    }

    override suspend fun findById(id: UserId): User? = newSuspendedTransaction(Dispatchers.IO) {
        UserTable.selectAll()
            .where { UserTable.id eq id.value }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<User> = newSuspendedTransaction(Dispatchers.IO) {
        UserTable.selectAll()
            .map { mapRowToUser(it) }
    }

    override suspend fun update(user: User): User = newSuspendedTransaction(Dispatchers.IO) {
        UserTable.update({ UserTable.id eq user.id!!.value }) {
            it[name] = user.name.value
            it[age] = user.age.value
        }
        user
    }

    override suspend fun delete(id: UserId): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        UserTable.deleteWhere { UserTable.id eq id.value } > 0
    }

    override suspend fun exists(id: UserId): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        UserTable.selectAll()
            .where { UserTable.id eq id.value }
            .limit(1)
            .count() > 0
    }

    private fun mapRowToUser(row: ResultRow): User {
        return User(
            id = UserId(row[UserTable.id]),
            name = UserName(row[UserTable.name]),
            age = UserAge(row[UserTable.age])
        )
    }
} 