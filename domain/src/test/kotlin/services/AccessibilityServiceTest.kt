package io.deepsearch.domain.services

import io.deepsearch.domain.config.domainTestModule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.test.KoinTest
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccessibilityServiceTest : KoinTest {

    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(domainTestModule)
    }

    private val accessibilityService: AccessibilityService by inject()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getAccessibilityElements should return scan result for valid URL`() = runTest {
        // Given
        val url = "https://www.otandp.com/body-check/"

        // When
        val result = accessibilityService.getAccessibilityElements(url)

        // Then
        assertNotNull(result)
        assertEquals("https://www.otandp.com/body-check/", result.url)
        assertTrue(result.scanTimestamp > 0)
        assertNotNull(result.violations)
        assertNotNull(result.passes)
        assertNotNull(result.incomplete)
    }
}