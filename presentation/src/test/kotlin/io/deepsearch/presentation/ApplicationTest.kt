package io.deepsearch.io.deepsearch.presentation

import io.deepsearch.presentation.module
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testRoot() = testApplication {
        application {
            module()
        }
        client.get("/users").apply {
            assertEquals(HttpStatusCode.Companion.OK, status)
        }
    }

}