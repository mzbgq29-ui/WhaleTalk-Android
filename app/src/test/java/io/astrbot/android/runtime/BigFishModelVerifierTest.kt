package io.astrbot.android.runtime

import io.astrbot.android.core.BigFishInput
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BigFishModelVerifierTest {
    private lateinit var server: MockWebServer
    private lateinit var catalog: BigFishModelCatalog
    private lateinit var verifier: BigFishModelVerifier
    private fun input() = BigFishInput("100001", server.url("/v1").toString(), "fixture-model", "sk-fixture-not-a-real-key")
    @Before fun start() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val testClient = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
        verifier = BigFishModelVerifier(testClient)
        catalog = BigFishModelCatalog(testClient)
    }
    @After fun stop() { server.shutdown() }
    @Test fun actualHttpsRequestContainsBoundedPromptAndOnlyHeaderContainsKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        verifier.verify(input())
        val received = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", received.path)
        assertEquals("Bearer " + input().key, received.getHeader("Authorization"))
        val body = received.body.readUtf8()
        assertFalse(body.contains(input().key)); assertEquals(16, JSONObject(body).getInt("max_tokens"))
        assertEquals(1, server.requestCount)
    }
    @Test fun authenticationFailureDoesNotExposeResponseOrRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Sensitive: " + input().key))
        val failure = runCatching { verifier.verify(input()) }.exceptionOrNull()
        assertNotNull(failure); assertTrue(failure!!.message!!.contains("认证")); assertFalse(failure.toString().contains(input().key))
        assertEquals(1, server.requestCount)
    }
    @Test fun redirectsAreRejectedWithoutSendingSecretsElsewhere() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.invalid/steal"))
        assertTrue(runCatching { verifier.verify(input()) }.exceptionOrNull()!!.message!!.contains("跳转"))
        assertEquals(1, server.requestCount)
    }
    @Test fun nullContentAndMalformedResponsesDoNotPassValidation() = runBlocking {
        for (body in listOf("not json", "{}", """{"choices":[{"message":{"content":null,"reasoning_content":null}}]}""")) {
            server.enqueue(MockResponse().setBody(body))
            assertTrue(runCatching { verifier.verify(input()) }.isFailure)
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun responseIsBounded() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(140000)))
        assertTrue(runCatching { verifier.verify(input()) }.exceptionOrNull()!!.message!!.contains("响应过大"))
    }
    @Test fun cancellationStopsWaitingForTheCloud() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { verifier.verify(input()) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        withTimeout(2000) { job.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }
    @Test fun modernReasoningModelUsesItsSupportedTokenParameter() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":null}}],"usage":{"completion_tokens":16}}"""))
        val base = input()
        verifier.verify(BigFishInput(base.owner, base.endpoint, "gpt-5-fixture", base.key))
        val request = JSONObject(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8())
        assertEquals(16, request.getInt("max_completion_tokens"))
        assertFalse(request.has("max_tokens"))
    }
    @Test fun modelListUsesReadOnlyRequestAndDeduplicatesIds() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"model-b"},{"id":"model-a"},{"id":"model-a"},{"id":null},{"id":""}]}"""))
        assertEquals(listOf("model-a", "model-b"), catalog.fetch(input().endpoint, input().key))
        val received = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", received.method); assertEquals("/v1/models", received.path)
        assertEquals("Bearer " + input().key, received.getHeader("Authorization"))
        assertEquals(0, received.bodySize)
    }
    @Test fun modelListFailureDoesNotExposeResponseOrFollowRedirects() = runBlocking {
        for (code in listOf(401, 403, 404, 302)) {
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", "https://example.invalid")
                .setBody(input().key))
            val failure = runCatching { catalog.fetch(input().endpoint, input().key) }.exceptionOrNull()
            assertNotNull(failure); assertFalse(failure.toString().contains(input().key))
        }
        assertEquals(4, server.requestCount)
    }
    @Test fun malformedEmptyAndOversizedModelListsGiveManualFallback() = runBlocking {
        for (body in listOf("bad json", "{}", """{"data":[]}""", "x".repeat(2100000))) {
            server.enqueue(MockResponse().setBody(body))
            assertTrue(runCatching { catalog.fetch(input().endpoint, input().key) }.isFailure)
        }
    }

}
