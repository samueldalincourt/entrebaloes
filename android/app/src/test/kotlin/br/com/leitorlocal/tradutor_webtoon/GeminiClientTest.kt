package br.com.leitorlocal.tradutor_webtoon

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiClientTest {
    private fun response(output: String, finish: String = "STOP") = JSONObject().put("candidates", org.json.JSONArray().put(
        JSONObject().put("finishReason", finish).put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", output)))))).toString()

    @Test fun sendsBoundedDialogueAndKeyOnlyInHeader() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(response("{\"b0\":\"Entre os moradores de __WT_NAME_0__.\"}")))
            val api = GeminiClient(baseUrl = server.url("/"))
            val result = api.translate("test-api-key-not-a-real-secret", GeminiClient.FLASH,
                JSONObject().put("b0", "Among those who call Nest home."), listOf("It is a place."), listOf("Nest"))
            assertTrue(result.contains("Nest"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("test-api-key-not-a-real-secret", request.getHeader("x-goog-api-key"))
            assertFalse(request.path!!.contains("key="))
            val raw = request.body.readUtf8()
            assertFalse(raw.contains("test-api-key"))
            assertFalse(raw.contains("inlineData"))
            val body = JSONObject(raw)
            assertEquals("low", body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
            assertEquals("b0", body.getJSONObject("generationConfig").getJSONObject("responseJsonSchema").getJSONArray("required").getString(0))
            val payload = JSONObject(body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals("Among those who call __WT_NAME_0__ home.", payload.getJSONObject("to_translate").getString("b0"))
            assertEquals("__WT_NAME_0__", payload.getJSONArray("preserve_terms").getString(0))
        } finally { server.shutdown() }
    }
    @Test fun protectsNamesInContextWithoutChangingPartsOfWordsOrExistingPlaceholders() {
        val input = JSONObject().put("b0", "Nest and NEST, not nested. __WT_NAME_0__")
        val protected = GeminiGlossary(input, listOf("Go to Nest."), listOf("Nest", "NEST"))
        val token = protected.protectedTerms.single()
        assertNotEquals("__WT_NAME_0__", token)
        assertEquals("Go to $token.", protected.protectedContext.single())
        assertEquals("$token and $token, not nested. __WT_NAME_0__", protected.protectedInput.getString("b0"))
        val output = JSONObject(protected.restore(JSONObject().put("b0", "$token e $token, sem aninhar. __WT_NAME_0__")))
        assertEquals("Nest e Nest, sem aninhar. __WT_NAME_0__", output.getString("b0"))
    }
    @Test fun rejectsDroppedDuplicatedOrMovedGlossaryNames() {
        val protected = GeminiGlossary(JSONObject().put("b0", "Nest is home.").put("b1", "Hello"), emptyList(), listOf("Nest"))
        val token = protected.protectedTerms.single()
        for (output in listOf(
            JSONObject().put("b0", "Ninho é nosso lar.").put("b1", "Olá"),
            JSONObject().put("b0", "$token $token é nosso lar.").put("b1", "Olá"),
            JSONObject().put("b0", "É nosso lar.").put("b1", token),
        )) {
            try { protected.restore(output); fail("Expected glossary validation failure") }
            catch (error: GeminiFailure) { assertEquals("TERMS", error.code) }
        }
    }
    @Test fun rejectsPartialAndMismatchedIds() {
        val api = GeminiClient()
        listOf(response("{\"b1\":\"Olá\"}"), response("{\"b0\":\"\"}"), response("{\"b0\":\"Olá\"}", "MAX_TOKENS"))
            .forEach { raw ->
                try { api.parse(raw, setOf("b0")); fail("Should reject invalid response") }
                catch (_: GeminiFailure) { }
            }
    }
    @Test fun sanitizesQuotaErrorsWithoutEchoingServerSecretsOrRetrying() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("private server error with api-key-secret"))
            try {
                GeminiClient(baseUrl = server.url("/")).translate("test-key", GeminiClient.LITE, JSONObject().put("b0", "Hello"), emptyList(), emptyList())
                fail("Expected quota failure")
            } catch (error: GeminiFailure) {
                assertEquals("QUOTA", error.code)
                assertEquals(429, error.httpStatus)
                assertFalse(error.message!!.contains("api-key-secret"))
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun distinguishesInactiveBillingFromTemporaryUnavailabilityWithoutRetrying() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = GeminiClient(baseUrl = server.url("/"))
            for ((status, expected) in listOf(402 to "BILLING", 503 to "SERVICE")) {
                server.enqueue(MockResponse().setResponseCode(status)
                    .setBody("{\"error\":{\"message\":\"private-key-and-dialogue\"}}"))
                try {
                    api.translate("test-key", GeminiClient.FLASH, JSONObject().put("b0", "Hello"), emptyList(), emptyList())
                    fail("Expected HTTP failure")
                } catch (error: GeminiFailure) {
                    assertEquals(expected, error.code)
                    assertEquals(status, error.httpStatus)
                    assertTrue(error.message!!.contains("HTTP $status"))
                    assertFalse(error.message!!.contains("private-key-and-dialogue"))
                    if (status == 402) {
                        assertTrue(error.message!!.contains("free tier"))
                        assertFalse(error.message!!.contains("indisponível"))
                    }
                }
            }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun classifiesStructuredApiErrorsWithoutEchoingArbitraryFields() {
        val api = GeminiClient()
        val invalidKey = api.httpFailure(400,
            """{"error":{"message":"secret-key","details":[{"reason":"API_KEY_INVALID"}]}}""")
        assertEquals("AUTH", invalidKey.code)
        assertFalse(invalidKey.message!!.contains("secret-key"))
        val precondition = api.httpFailure(400, """{"error":{"status":"FAILED_PRECONDITION"}}""")
        assertEquals("SETUP", precondition.code)
        assertTrue(precondition.message!!.contains("free tier"))
        assertEquals("REQUEST", api.httpFailure(400, "not JSON").code)
        assertEquals("AUTH", api.httpFailure(403, "").code)
        assertEquals("MODEL", api.httpFailure(404, "").code)
        assertEquals("HTTP", api.httpFailure(302, "").code)
    }
    @Test fun cancelsInFlightRequestWhenReadingIsInvalidated() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = GeminiClient(baseUrl = server.url("/"))
            val call = async(Dispatchers.IO) { api.translate("test-key", GeminiClient.LITE, JSONObject().put("b0", "Hello"), emptyList(), emptyList()) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            withTimeout(2000) { call.cancelAndJoin() }
            assertTrue(call.isCancelled)
        } finally { server.shutdown() }
    }
    @Test fun doesNotForwardCredentialsThroughRedirects() = runBlocking {
        val source = MockWebServer().apply { start() }
        val other = MockWebServer().apply { start() }
        try {
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/capture")))
            try {
                GeminiClient(baseUrl = source.url("/")).translate("test-key", GeminiClient.FLASH, JSONObject().put("b0", "Hello"), emptyList(), emptyList())
                fail("Expected redirect refusal")
            } catch (_: GeminiFailure) { }
            assertEquals(1, source.requestCount)
            assertNull(other.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { source.shutdown(); other.shutdown() }
    }
    @Test fun rejectsMalformedHeaderCredentialsWithoutEchoingThem() = runBlocking {
        val secret = "fake-secret-with-unicode-\u200b"
        try {
            GeminiClient().translate(secret, GeminiClient.FLASH, JSONObject().put("b0", "Hello"), emptyList(), emptyList())
            fail("Expected invalid credential rejection")
        } catch (error: IllegalArgumentException) {
            assertFalse(error.message!!.contains(secret))
            assertFalse(error.message!!.contains("fake-secret"))
        }
    }
}
