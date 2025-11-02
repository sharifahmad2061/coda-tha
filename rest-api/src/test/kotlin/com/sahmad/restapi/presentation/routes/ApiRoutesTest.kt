package com.sahmad.restapi.presentation.routes

import com.sahmad.restapi.infrastructure.serialization.configureSerialization
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

class ApiRoutesTest {
    @Test
    fun `health endpoint returns healthy status`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("healthy"))
        }

    @Test
    fun `config delay endpoint sets delay successfully`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val response =
                client.post("/config/delay") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"delayMs": 1000}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Delay configured"))
            assertTrue(body.contains("1000"))
        }

    @Test
    fun `get config endpoint returns current delay configuration`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            // First set a delay
            client.post("/config/delay") {
                contentType(ContentType.Application.Json)
                setBody("""{"delayMs": 500}""")
            }

            // Then get the config
            val response = client.get("/config")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("500"))
        }

    @Test
    fun `post to any path echoes request body as JSON`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val requestBody = """{"test": "data", "number": 123}"""
            val response =
                client.post("/test/path") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertEquals(requestBody, responseBody)
        }

    @Test
    fun `post to nested path echoes request body`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val requestBody = """{"nested": "path", "test": true}"""
            val response =
                client.post("/api/v1/users/create") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertEquals(requestBody, responseBody)
        }

    @Test
    fun `post with empty body echoes empty response`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val response =
                client.post("/test") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertEquals("", responseBody)
        }

    @Test
    fun `delay configuration persists across requests`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            // Set delay
            client.post("/config/delay") {
                contentType(ContentType.Application.Json)
                setBody("""{"delayMs": 100}""")
            }

            // Check config multiple times
            val response1 = client.get("/config")
            assertTrue(response1.bodyAsText().contains("100"))

            val response2 = client.get("/config")
            assertTrue(response2.bodyAsText().contains("100"))
        }

    @Test
    fun `delay can be updated multiple times`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            // Set initial delay
            client.post("/config/delay") {
                contentType(ContentType.Application.Json)
                setBody("""{"delayMs": 100}""")
            }

            var response = client.get("/config")
            assertTrue(response.bodyAsText().contains("100"))

            // Update delay
            client.post("/config/delay") {
                contentType(ContentType.Application.Json)
                setBody("""{"delayMs": 200}""")
            }

            response = client.get("/config")
            assertTrue(response.bodyAsText().contains("200"))
        }

    @Test
    fun `post request with large body echoes correctly`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val largeBody = """{"data": "${"x".repeat(1000)}"}"""
            val response =
                client.post("/test") {
                    contentType(ContentType.Application.Json)
                    setBody(largeBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = response.bodyAsText()
            assertEquals(largeBody, responseBody)
            assertTrue(responseBody.length > 1000)
        }

    @Test
    fun `post with special characters in body echoes correctly`() =
        testApplication {
            application {
                configureSerialization()
                configureApiRouting()
            }

            val specialBody = """{"data": "test with 中文 and émojis 🚀"}"""
            val response =
                client.post("/test") {
                    contentType(ContentType.Application.Json)
                    setBody(specialBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(specialBody, response.bodyAsText())
        }
}
