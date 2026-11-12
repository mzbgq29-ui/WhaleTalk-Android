package io.astrbot.android.runtime

import io.astrbot.android.core.LocalEndpoint
import io.astrbot.android.core.QqState
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SnowLumaHealthTest {
    private lateinit var server: MockWebServer
    private lateinit var health: SnowLumaHealth
    @Before fun start() {
        server = MockWebServer(); server.start()
        health = SnowLumaHealth(OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build(), LocalEndpoint(server.port))
    }
    @After fun stop() { server.shutdown() }
    private fun reply(body: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(body)) }
    private fun login() = reply("""{"success":true,"token":"session-fixture","mustChangePassword":false}""")
    @Test fun pageAndEmptyAccountListNeverMeanLoggedIn() {
        login(); reply("""{"list":[]}""")
        assertEquals(QqState.LOGGED_OUT, health.query("Az7!private-fixture").state)
        val auth = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/login", auth.path)
        assertEquals("Az7!private-fixture", JSONObject(auth.body.readUtf8()).getString("password"))
        val list = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer session-fixture", list.getHeader("Authorization"))
        assertEquals("/api/qq-list", list.path)
    }
    @Test fun onlineRequiresAnAuthenticatedAccountAndRealStatusAction() {
        login(); reply("""{"list":[{"uin":"12345678","nickname":"fixture"}]}""")
        reply("""{"status":"ok","retcode":0,"data":{"online":true}}""")
        val result = health.query("private-fixture")
        assertEquals(QqState.ONLINE, result.state); assertEquals("12345678", result.account)
        server.takeRequest(); server.takeRequest()
        val action = server.takeRequest()!!
        assertEquals("/api/debug/invoke", action.path)
        assertEquals("get_status", JSONObject(action.body.readUtf8()).getString("action"))
        assertEquals("Bearer session-fixture", action.getHeader("Authorization"))
    }
    @Test fun staleOrOfflineAccountsAreNotReportedOnline() {
        login(); reply("""{"list":[{"uin":"12345678"}]}""")
        reply("""{"status":"ok","retcode":0,"data":{"online":false}}""")
        assertEquals(QqState.OFFLINE, health.query("private-fixture").state)
        reply("""{"list":[{"uin":"12345678"}]}""")
        reply("""{"status":"failed","message":"账号不在线"}""",404)
        assertEquals(QqState.UNKNOWN, health.query("private-fixture").state)
    }
    @Test fun authAndPasswordChangeGatesArePreserved() {
        reply("""{"success":true,"token":"change-only","mustChangePassword":true}""")
        assertEquals(QqState.UNKNOWN, health.query("private-fixture").state)
        assertEquals(1, server.requestCount)
        reply("""{"error":"fixture"}""",401)
        assertEquals(QqState.UNKNOWN, health.query("private-fixture").state)
        assertEquals(2, server.requestCount)
    }
}
