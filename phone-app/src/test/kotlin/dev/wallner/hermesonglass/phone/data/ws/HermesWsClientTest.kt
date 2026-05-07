package dev.wallner.hermesonglass.phone.data.ws

import app.cash.turbine.test
import com.google.gson.Gson
import dev.wallner.hermesonglass.shared.AssistantChunk
import dev.wallner.hermesonglass.shared.ClientHello
import dev.wallner.hermesonglass.shared.ServerWelcome
import dev.wallner.hermesonglass.shared.SessionInfo
import dev.wallner.hermesonglass.shared.UserMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [HermesWsClient] using OkHttp's MockWebServer for WebSocket.
 *
 * Each test spins up a fresh MockWebServer, scripts the listener, and asserts
 * either the inbound state machine or the framing on the wire. Tests run on
 * the JVM — no Android components touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesWsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private val openServerSockets = mutableListOf<WebSocket>()
    private val activeClients = mutableListOf<HermesWsClient>()
    private val gson = Gson()

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    @After fun teardown() {
        // Order matters: shut down the client first so it sends a clean close
        // frame; then close any server-side WebSockets we still hold; then drain
        // OkHttp's dispatcher; finally tell MockWebServer to shut down. Without
        // this MockWebServer.shutdown() blocks 5 s on its queue and then throws.
        activeClients.forEach { runCatching { it.shutdown() } }
        openServerSockets.forEach { runCatching { it.close(1000, "test teardown") } }
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    private fun wsUrl(): String =
        server.url("/glasses").toString().replace("http://", "ws://").replace("https://", "wss://")

    private fun newClient(secret: String = "test-secret"): HermesWsClient {
        val client = HermesWsClient(
            url = wsUrl(),
            sharedSecret = secret,
            deviceId = "phone-test",
            httpClient = httpClient,
            initialBackoffMs = 50,
            maxBackoffMs = 200,
        )
        activeClients.add(client)
        return client
    }

    private inner class ServerSide {
        val received = LinkedBlockingQueue<String>()
        @Volatile var ws: WebSocket? = null

        fun listener(): WebSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                openServerSockets.add(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
        }
    }

    @Test fun `bearer header is sent on upgrade`() = runBlocking {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient(secret = "secret-xyz")
        try {
            client.start()
            val req = withTimeout(2_000) {
                runBlocking { server.takeRequest() }
            }
            assertEquals("Bearer secret-xyz", req.getHeader("Authorization"))
        } finally {
            client.shutdown()
        }
    }

    @Test fun `connects and emits client_hello first`() = runBlocking {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        try {
            client.start()
            val firstFrame = side.received.poll(2, TimeUnit.SECONDS)
            assertNotNull("server should have received the hello", firstFrame)
            val parsed = gson.fromJson(firstFrame, ClientHello::class.java)
            assertEquals("client_hello", parsed.type)
            assertEquals(1, parsed.protocolVersion)
            assertEquals("phone-test", parsed.deviceId)
        } finally {
            client.shutdown()
        }
    }

    @Test fun `server_welcome arrives on frames flow`() = runTest {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        client.frames.test {
            client.start()
            val sentToServer = side.received.poll(2, TimeUnit.SECONDS)
            assertNotNull(sentToServer)
            val welcome = ServerWelcome(
                protocolVersion = 1,
                sessions = listOf(SessionInfo("s1")),
                currentSessionKey = "s1",
            )
            side.ws!!.send(gson.toJson(welcome))

            val received = awaitItem()
            assertTrue(received is ServerWelcome)
            assertEquals("s1", (received as ServerWelcome).currentSessionKey)
            cancelAndIgnoreRemainingEvents()
        }
        client.shutdown()
    }

    @Test fun `state transitions Disconnected to Connecting to Connected`() = runTest {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        client.state.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())
            client.start()
            assertEquals(ConnectionState.Connecting, awaitItem())
            assertEquals(ConnectionState.Connected, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        client.shutdown()
    }

    @Test fun `send returns false when not connected`() {
        val client = newClient()
        try {
            val ok = client.send(
                UserMessage(id = "m1", text = "hi"),
            )
            assertFalse(ok)
        } finally {
            client.shutdown()
        }
    }

    @Test fun `send delivers JSON to server when connected`() = runBlocking {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        try {
            client.start()
            // Drain the hello.
            side.received.poll(2, TimeUnit.SECONDS)
            // Wait briefly for state to settle to Connected.
            withTimeout(2_000) {
                while (client.state.value != ConnectionState.Connected) {
                    kotlinx.coroutines.delay(10)
                }
            }
            val ok = client.send(UserMessage(id = "m1", text = "hi"))
            assertTrue(ok)
            val raw = side.received.poll(2, TimeUnit.SECONDS)
            assertNotNull(raw)
            val parsed = gson.fromJson(raw, UserMessage::class.java)
            assertEquals("m1", parsed.id)
            assertEquals("hi", parsed.text)
        } finally {
            client.shutdown()
        }
    }

    @Test fun `unauthorized 401 surfaces as Failed`() = runTest {
        // Mock server replies with a non-101 to fail the upgrade.
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("unauthorized"),
        )

        val client = newClient(secret = "wrong")
        client.state.test {
            assertEquals(ConnectionState.Disconnected, awaitItem())
            client.start()
            assertEquals(ConnectionState.Connecting, awaitItem())
            val failed = awaitItem()
            assertTrue("expected Failed, got $failed", failed is ConnectionState.Failed)
            assertTrue((failed as ConnectionState.Failed).reason.contains("401"))
            cancelAndIgnoreRemainingEvents()
        }
        client.shutdown()
    }

    @Test fun `reconnect attempts after transient failure`() = runBlocking {
        // First upgrade fails with 5xx. Second upgrade succeeds.
        server.enqueue(MockResponse().setResponseCode(503))
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        try {
            client.start()
            // Wait for a successful client_hello on the second attempt.
            val firstFrame = side.received.poll(5, TimeUnit.SECONDS)
            assertNotNull("client should reconnect after 503 and send hello", firstFrame)
            assertEquals(ConnectionState.Connected, client.state.value)
        } finally {
            client.shutdown()
        }
    }

    @Test fun `manual stop transitions to Disconnected and stops reconnect`() = runBlocking {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        try {
            client.start()
            side.received.poll(2, TimeUnit.SECONDS)
            withTimeout(2_000) {
                while (client.state.value != ConnectionState.Connected) {
                    kotlinx.coroutines.delay(10)
                }
            }
            client.stop()
            assertEquals(ConnectionState.Disconnected, client.state.value)
        } finally {
            client.shutdown()
        }
    }

    @Test fun `inbound assistant_chunk is parsed and emitted`() = runTest {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        client.frames.test {
            client.start()
            side.received.poll(2, TimeUnit.SECONDS)
            val chunk = AssistantChunk(id = "m1", chunk = "hello", parentId = null)
            side.ws!!.send(gson.toJson(chunk))
            val received = awaitItem()
            assertTrue(received is AssistantChunk)
            assertEquals("hello", (received as AssistantChunk).chunk)
            cancelAndIgnoreRemainingEvents()
        }
        client.shutdown()
    }

    @Test fun `malformed inbound JSON is silently dropped`() = runTest {
        val side = ServerSide()
        server.enqueue(MockResponse().withWebSocketUpgrade(side.listener()))

        val client = newClient()
        try {
            client.start()
            side.received.poll(2, TimeUnit.SECONDS)
            // Send garbage; client must not crash and the frames flow stays empty.
            side.ws!!.send("{not-json")
            // Then send a real frame to confirm we still process subsequent frames.
            side.ws!!.send(gson.toJson(AssistantChunk(id = "m2", chunk = "ok")))

            client.frames.test {
                val received = awaitItem()
                assertEquals("m2", (received as AssistantChunk).id)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            client.shutdown()
        }
    }
}
