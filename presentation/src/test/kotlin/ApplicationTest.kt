package io.deepsearch

import io.deepsearch.presentation.module
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        client.get("/users").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }

}
