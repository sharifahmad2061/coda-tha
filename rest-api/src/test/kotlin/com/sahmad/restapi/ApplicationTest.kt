package com.sahmad.restapi

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `application module configures all routes correctly`() =
        testApplication {
            application {
                module()
            }

            // Test health endpoint
            val healthResponse = client.get("/health")
            assertEquals(HttpStatusCode.OK, healthResponse.status)
            assertTrue(healthResponse.bodyAsText().contains("healthy"))

            // Test config endpoints
            val configResponse = client.get("/config")
            assertEquals(HttpStatusCode.OK, configResponse.status)

            // Test delay configuration
            val delayResponse =
                client.post("/config/delay") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"delayMs": 100}""")
                }
            assertEquals(HttpStatusCode.OK, delayResponse.status)

            // Test POST echo endpoint
            val echoResponse =
                client.post("/test") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"test": "data"}""")
                }
            assertEquals(HttpStatusCode.OK, echoResponse.status)
        }

    @Test
    fun `application handles complete workflow`() =
        testApplication {
            application {
                module()
            }

            // 1. Check health
            val health = client.get("/health")
            assertEquals(HttpStatusCode.OK, health.status)

            // 2. Get initial config (should be 0)
            val initialConfig = client.get("/config")
            assertTrue(initialConfig.bodyAsText().contains("0"))

            // 3. Set delay
            client.post("/config/delay") {
                contentType(ContentType.Application.Json)
                setBody("""{"delayMs": 50}""")
            }

            // 4. Verify config changed
            val updatedConfig = client.get("/config")
            assertTrue(updatedConfig.bodyAsText().contains("50"))

            // 5. Make POST request
            val requestBody = """{"workflow": "test", "step": 5}"""
            val response =
                client.post("/api/process") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(requestBody, response.bodyAsText())
        }

    @Test
    fun `application handles concurrent requests correctly`() =
        testApplication {
            application {
                module()
            }

            // Send multiple concurrent requests
            val responses =
                (1..10).map { index ->
                    client.post("/test/$index") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"request": $index}""")
                    }
                }

            responses.forEachIndexed { index, response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val body = response.bodyAsText()
                assertTrue(body.contains((index + 1).toString()))
            }
        }

    @Test
    fun `application handles various paths correctly`() =
        testApplication {
            application {
                module()
            }

            val paths = listOf("/test", "/api/v1", "/deep/nested/path")

            paths.forEach { path ->
                val response =
                    client.post(path) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"path": "$path"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
            }
        }

    @Test
    fun `application preserves request body integrity`() =
        testApplication {
            application {
                module()
            }

            val complexBody =
                """
                {
                    "string": "value with spaces and special chars !@#$%",
                    "number": 123.456,
                    "boolean": true,
                    "null": null,
                    "array": [1, 2, 3],
                    "nested": {
                        "deep": {
                            "value": "test"
                        }
                    }
                }
                """.trimIndent()

            val response =
                client.post("/test") {
                    contentType(ContentType.Application.Json)
                    setBody(complexBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()

            // Verify all key elements are preserved
            assertTrue(responseBody.contains("string"))
            assertTrue(responseBody.contains("value with spaces and special chars"))
            assertTrue(responseBody.contains("123.456"))
            assertTrue(responseBody.contains("true"))
            assertTrue(responseBody.contains("array"))
            assertTrue(responseBody.contains("nested"))
            assertTrue(responseBody.contains("deep"))
        }
}
