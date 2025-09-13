package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.entities.User
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.domain.models.valueobjects.UserId
import io.deepsearch.domain.models.valueobjects.UserName
import io.deepsearch.domain.models.valueobjects.UserAge
import io.deepsearch.infrastructure.database.UserTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class ExposedUserRepository : IUserRepository {
    
    override suspend fun save(user: User): User = suspendTransaction {
        val id = UserTable.insert {
            it[name] = user.name.value
            it[age] = user.age.value
        }[UserTable.id]
        
        user.withId(UserId(id))
    }

    override suspend fun findById(id: UserId): User? = suspendTransaction {
        UserTable.selectAll()
            .where { UserTable.id eq id.value }
            .map { mapRowToUser(it) }
            .singleOrNull()
    }

    override suspend fun findAll(): List<User> = suspendTransaction {
        UserTable.selectAll()
            .map { mapRowToUser(it) }
            .toList()
    }

    override suspend fun update(user: User): User = suspendTransaction {
        UserTable.update({ UserTable.id eq user.id!!.value }) {
            it[name] = user.name.value
            it[age] = user.age.value
        }
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
            name = UserName(row[UserTable.name]),
            age = UserAge(row[UserTable.age])
        )
    }
} 