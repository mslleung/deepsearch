package io.deepsearch.infrastructure.repositories

import io.deepsearch.domain.exceptions.OptimisticLockException
import io.deepsearch.domain.models.entities.User
import io.deepsearch.domain.models.valueobjects.Email
import io.deepsearch.domain.models.valueobjects.PasswordHash
import io.deepsearch.domain.repositories.IUserRepository
import io.deepsearch.infrastructure.config.infrastructureTestModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import io.deepsearch.domain.testing.IsolatedKoinExtension
import io.deepsearch.domain.testing.IsolatedKoinTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.test.assertFailsWith

@OptIn(ExperimentalTime::class)
class ExposedUserRepositoryTest : IsolatedKoinTest() {

    @JvmField
    @RegisterExtension
    val koinExtension = IsolatedKoinExtension.create {
        modules(infrastructureTestModule)
    }

    private val testCoroutineDispatcher by inject<CoroutineDispatcher>()
    private val userRepository by inject<IUserRepository>()

    @Test
    fun `update increments version`() = runTest(testCoroutineDispatcher) {
        val user = userRepository.save(
            User(
                email = Email("optimistic@example.com"),
                passwordHash = PasswordHash("hash")
            )
        )

        assertEquals(0, user.version, "Newly persisted user should start at version 0")

        user.updatePassword(PasswordHash("hash-2"), Clock.System.now())
        val updatedUser = userRepository.update(user)

        assertEquals(1, updatedUser.version, "Updating a user should increment the version")
    }

    @Test
    fun `stale update throws optimistic lock exception`() = runTest(testCoroutineDispatcher) {
        val baseline = userRepository.save(
            User(
                email = Email("conflict@example.com"),
                passwordHash = PasswordHash("hash")
            )
        )

        val persisted = userRepository.findById(baseline.id!!)!!

        baseline.updatePassword(PasswordHash("hash-new"), Clock.System.now())
        userRepository.update(baseline)

        persisted.updatePassword(PasswordHash("hash-stale"), Clock.System.now())

        assertFailsWith<OptimisticLockException> {
            userRepository.update(persisted)
        }
    }
}

