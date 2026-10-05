package io.horizontalsystems.solanakit.network

import com.solana.networking.HttpRpcResponse
import com.solana.networking.Network
import com.solana.networking.RPCEndpoint
import com.solana.networking.RpcError
import com.solana.networking.RpcRequest
import com.solana.networking.postJsonRpc
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class FailoverRpcRouterTest {

    private val server = RpcServer()
    private val timeSource = TestTimeSource()
    private val errors = CopyOnWriteArrayList<SolanaNetworkError>()
    private val methodCounter = AtomicInteger()

    @After
    fun tearDown() {
        server.close()
        unmockkStatic(NETWORKING_ROUTER_KT)
    }

    @Test
    fun execute_keyUnauthorizedOrForbidden_bansKeyForAnHourAndUsesNextKey() = runBlocking {
        listOf(401, 403).forEach { status ->
            val keys = rotation(KEY_A, KEY_B)
            server.reply(KEY_A, status, "denied")
            server.reply(KEY_B, 200, result(7))

            assertEquals(RpcOutcome.Success(7), router(keys).execute(request(), Int.serializer()))
            assertBannedFor(keys, KEY_A, 1.hours)
        }
    }

    @Test
    fun execute_keyRateLimitedWithRetryAfter_bansForRetryAfter() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(KEY_A, 429, "slow down", retryAfter = 5)
        server.reply(KEY_B, 200, result(7))

        assertEquals(RpcOutcome.Success(7), router(keys).execute(request(), Int.serializer()))
        assertBannedFor(keys, KEY_A, 5.seconds)
    }

    @Test
    fun execute_keyRateLimitedWithoutRetryAfter_bansForMinute() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(KEY_A, 429, "slow down")
        server.reply(KEY_B, 200, result(7))

        router(keys).execute(request(), Int.serializer())

        assertBannedFor(keys, KEY_A, 60.seconds)
    }

    @Test
    fun execute_keyServerError_bansFor30Seconds() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(KEY_A, 503, "unavailable")
        server.reply(KEY_B, 200, result(7))

        assertEquals(RpcOutcome.Success(7), router(keys).execute(request(), Int.serializer()))
        assertBannedFor(keys, KEY_A, 30.seconds)
    }

    @Test
    fun execute_keyTransportFailure_bansFor30SecondsAndUsesNextKey() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        failTransportFor(KEY_A)
        server.reply(KEY_B, 200, result(7))

        assertEquals(RpcOutcome.Success(7), router(keys).execute(request(), Int.serializer()))
        assertBannedFor(keys, KEY_A, 30.seconds)
    }

    @Test
    fun execute_banExpiresDuringSlowKeyedFailures_triesEachKeyOnceThenPublic() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        val keyedAttempts = failSlowlyOnKeys()
        server.reply(PUBLIC, 200, result(7))

        val outcome = withTimeout(5.seconds) { router(keys).execute(request(), Int.serializer()) }

        assertEquals(RpcOutcome.Success(7), outcome)
        assertEquals(2, keyedAttempts.get())
    }

    @Test
    fun executeBatch_banExpiresDuringSlowKeyedFailures_triesEachKeyOnce() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        val keyedAttempts = failSlowlyOnKeys()

        val outcome = withTimeout(5.seconds) { router(keys).executeBatch(listOf(request("a")), Int.serializer()) }

        assertEquals(BatchOutcome.KeyedUnavailable, outcome)
        assertEquals(2, keyedAttempts.get())
    }

    @Test
    fun execute_okResponseWithNodeQuotaError_bansKeyForMinute() = runBlocking {
        val quotaErrors = listOf(
            nodeError(429, "Too many requests"),
            nodeError(-32005, "Your app has exceeded its compute units per second capacity"),
            nodeError(-32600, "Monthly QUOTA exceeded"),
            nodeError(-32000, "Rate limit reached"),
        )
        quotaErrors.forEach { body ->
            val keys = rotation(KEY_A, KEY_B)
            server.reply(KEY_A, 200, body)
            server.reply(KEY_B, 200, result(7))

            assertEquals(RpcOutcome.Success(7), router(keys).execute(request(), Int.serializer()))
            assertBannedFor(keys, KEY_A, 60.seconds)
        }
    }

    @Test
    fun execute_keyedNodeErrorNotQuota_returnedWithoutBan() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(KEY_A, 200, nodeError(-32602, "Invalid params"))

        val outcome = router(keys).execute(request(), Int.serializer())

        assertEquals(RpcOutcome.NodeError(RpcError(-32602, "Invalid params")), outcome)
        assertTrue(keys.hasHealthyKey())
        assertEquals(setOf(KEY_A, KEY_B), healthyKeys(keys))
    }

    @Test
    fun execute_allKeysBanned_usesPublicEndpoint() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(KEY_A, 401, "denied")
        server.reply(KEY_B, 403, "denied")
        server.reply(PUBLIC, 200, result(9))

        val router = router(keys)

        assertEquals(RpcOutcome.Success(9), router.execute(request(), Int.serializer()))
        assertFalse(router.isKeyedAvailable())
        assertEquals(listOf("/v2/$KEY_A", "/v2/$KEY_B", "/$PUBLIC"), server.hits)
    }

    @Test
    fun execute_publicOnly_skipsKeys() = runBlocking {
        server.reply(PUBLIC, 200, result(9))

        assertEquals(RpcOutcome.Success(9), router(rotation(KEY_A)).execute(request(), Int.serializer(), publicOnly = true))
        assertEquals(listOf("/$PUBLIC"), server.hits)
    }

    @Test
    fun execute_publicFailures_returnedTyped() = runBlocking {
        val router = router(rotation())

        server.reply(PUBLIC, 500, "boom")
        assertEquals(RpcOutcome.HttpFailure(500, null, "boom"), router.execute(request(), Int.serializer(), publicOnly = true))

        server.reply(PUBLIC, 200, "not json")
        assertTrue(router.execute(request(), Int.serializer(), publicOnly = true) is RpcOutcome.DecodeFailure)

        server.reply(PUBLIC, 200, nodeError(-32009, "Slot skipped"))
        assertEquals(
            RpcOutcome.NodeError(RpcError(-32009, "Slot skipped")),
            router.execute(request(), Int.serializer(), publicOnly = true),
        )

        server.reply(PUBLIC, 200, result(1))
        assertTrue(router.execute(request(), ThrowingSerializer, publicOnly = true) is RpcOutcome.DecodeFailure)

        failTransportFor(PUBLIC)
        val transport = router.execute(request(), Int.serializer(), publicOnly = true)
        assertTrue(transport is RpcOutcome.TransportFailure)
        assertEquals("solana-rpc", errors.single().source)
    }

    @Test
    fun endpoint_withKeys_isPublicEndpoint() {
        val router = router(rotation(KEY_A))

        assertSame(publicEndpoint, router.endpoint)
    }

    @Test
    fun makeRequest_outcomes_keepHttpRouterContract() = runBlocking {
        val router = router(rotation())

        server.reply(PUBLIC, 200, result(7))
        assertEquals(7, router.makeRequest(request(), Int.serializer()).result)

        server.reply(PUBLIC, 200, nodeError(-32602, "Invalid params"))
        assertEquals(RpcError(-32602, "Invalid params"), router.makeRequest(request(), Int.serializer()).error)

        server.reply(PUBLIC, 429, "{\"error\":{\"code\":429}}", retryAfter = 7)
        val http = router.makeRequest(request(), Int.serializer()).error
        assertEquals(-1, http?.code)
        assertEquals("{\"error\":{\"code\":429}}", http?.message)
        assertEquals(7L, http?.retryAfter)

        server.reply(PUBLIC, 200, "not json")
        assertEquals(-1, router.makeRequest(request(), Int.serializer()).error?.code)

        failTransportFor(PUBLIC, message = "connection reset")
        val transport = router.makeRequest(request(), Int.serializer())
        assertEquals(RpcError(-1, "connection reset"), transport.error)
        assertNull(transport.result)
    }

    @Test
    fun executeBatch_okResponse_itemsMatchedById() = runBlocking {
        server.reply(
            KEY_A, 200,
            "[${nodeError(-32602, "Invalid params", id = "b")},${result(1, id = "a")}]",
        )

        val outcome = router(rotation(KEY_A)).executeBatch(
            listOf(request("a"), request("b"), request("c")),
            Int.serializer(),
        )

        assertEquals(
            BatchOutcome.Results(
                listOf(
                    RpcOutcome.Success(1),
                    RpcOutcome.NodeError(RpcError(-32602, "Invalid params")),
                    RpcOutcome.DecodeFailure("No response for request c"),
                )
            ),
            outcome,
        )
        assertTrue(server.bodies.single().startsWith("["))
    }

    @Test
    fun executeBatch_itemRateLimited_bansKeyAndMarksItemsRateLimited() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(
            KEY_A, 200,
            "[${result(1, id = "a")},${nodeError(-32005, "compute units capacity exceeded", id = "b")}]",
        )

        val outcome = router(keys).executeBatch(listOf(request("a"), request("b")), Int.serializer())

        assertEquals(BatchOutcome.Results(listOf(RpcOutcome.Success(1), RpcOutcome.RateLimited)), outcome)
        assertBannedFor(keys, KEY_A, 60.seconds)
    }

    @Test
    fun executeBatch_nonOkResponse_retriedOnNextKey() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        server.reply(KEY_A, 503, "unavailable")
        server.reply(KEY_B, 200, "[${result(1, id = "a")}]")

        val outcome = router(keys).executeBatch(listOf(request("a")), Int.serializer())

        assertEquals(BatchOutcome.Results(listOf(RpcOutcome.Success(1))), outcome)
        assertEquals(listOf("/v2/$KEY_A", "/v2/$KEY_B"), server.hits)
        assertBannedFor(keys, KEY_A, 30.seconds)
    }

    @Test
    fun executeBatch_allKeysBanned_keyedUnavailableWithoutPublicRequest() = runBlocking {
        server.reply(KEY_A, 401, "denied")
        server.reply(PUBLIC, 200, "[${result(1, id = "a")}]")

        val outcome = router(rotation(KEY_A)).executeBatch(listOf(request("a")), Int.serializer())

        assertEquals(BatchOutcome.KeyedUnavailable, outcome)
        assertEquals(listOf("/v2/$KEY_A"), server.hits)
        assertEquals(
            BatchOutcome.KeyedUnavailable,
            router(rotation()).executeBatch(listOf(request("a")), Int.serializer()),
        )
        assertEquals(listOf("/v2/$KEY_A"), server.hits)
    }

    @Test
    fun withDirectUrl_keyedSuccess_publicNotUsed() = runBlocking {
        val urls = CopyOnWriteArrayList<URL>()

        router(rotation(KEY_A)).withDirectUrl("getLatestBlockhash") { url -> urls.add(url) }

        assertEquals(listOf(server.alchemy.url(KEY_A)), urls)
    }

    @Test
    fun withDirectUrl_keyedFailure_bansKeyAndRetriesOnceOnPublic() = runBlocking {
        val keys = rotation(KEY_A, KEY_B)
        val urls = CopyOnWriteArrayList<URL>()

        val result = router(keys).withDirectUrl("sendTransaction") { url ->
            urls.add(url)
            if (url != publicEndpoint.url) throw IOException("refused")
            "signature"
        }

        assertEquals("signature", result)
        assertEquals(listOf(server.alchemy.url(KEY_A), publicEndpoint.url), urls)
        assertBannedFor(keys, KEY_A, 30.seconds)
    }

    @Test
    fun pacer_sharedByRouterAndDirectTransport_sameMethodOneSecondApart() = runBlocking {
        server.reply(PUBLIC, 200, result(1))
        val router = router(rotation())

        router.execute(request(method = "sendTransaction"), Int.serializer())
        val afterFirst = TimeSource.Monotonic.markNow()
        val secondStart = router.withDirectUrl("sendTransaction") { TimeSource.Monotonic.markNow() }

        assertTrue(secondStart - afterFirst >= 900.milliseconds)
    }

    @Test
    fun secrecy_keyedFailures_neverSurfaceKey() = runBlocking {
        val keys = rotation(SENTINEL)
        val router = router(keys)

        // Transport failure whose message holds the keyed URL, as HttpURLConnection reports it.
        failTransportFor(SENTINEL, message = "Server returned HTTP response code: 500 for URL: ${server.alchemy.url(SENTINEL)}")
        server.reply(PUBLIC, 200, result(1))
        router.makeRequest(request(), Int.serializer())
        unmockkStatic(NETWORKING_ROUTER_KT)

        // 403 body and node error message echoing the key.
        timeSource += 2.hours
        server.reply(SENTINEL, 403, "Invalid API key: $SENTINEL")
        server.reply(PUBLIC, 500, "public down")
        val afterForbidden = router.makeRequest(request(), Int.serializer())

        timeSource += 2.hours
        server.reply(SENTINEL, 200, nodeError(-32602, "Bad request for key $SENTINEL"))
        val nodeError = router.makeRequest(request(), Int.serializer())

        server.reply(SENTINEL, 200, "[${nodeError(-32602, "Bad item for $SENTINEL", id = "a")}]")
        val batch = router.executeBatch(listOf(request("a")), Int.serializer()) as BatchOutcome.Results

        val failing = rotation(SENTINEL)
        router(failing).withDirectUrl("sendTransaction") { url ->
            if (url != publicEndpoint.url) throw IOException("Server returned HTTP response code: 401 for URL: $url")
            "signature"
        }

        val surfaced = errors.flatMap { listOf(it.url, it.throwable.stackTraceToString()) } +
            afterForbidden.error.toString() +
            nodeError.error.toString() +
            batch.items.toString()
        assertEquals(2, errors.size)
        errors.forEach { assertNull(it.throwable.cause) }
        surfaced.forEach { assertFalse(it, it.contains(SENTINEL)) }
        assertTrue(nodeError.error?.message.orEmpty().contains("***"))
        assertTrue(batch.items.toString().contains("***"))
    }

    private val publicEndpoint by lazy {
        RPCEndpoint.custom(server.url(PUBLIC), server.url(PUBLIC), Network.mainnetBeta)
    }

    private fun router(keys: ApiKeyRotation) = FailoverRpcRouter(
        publicEndpoint = publicEndpoint,
        keys = keys,
        alchemy = server.alchemy,
        pacer = PublicPacer(TimeSource.Monotonic),
        networkErrorListener = { errors.add(it) },
    )

    private fun rotation(vararg keys: String) = ApiKeyRotation(keys.toList(), FirstIndexRandom, timeSource)

    private fun healthyKeys(keys: ApiKeyRotation): Set<String> = List(4) { keys.nextKey() }.filterNotNull().toSet()

    private fun assertBannedFor(keys: ApiKeyRotation, key: String, duration: Duration) {
        timeSource += duration - 1.milliseconds
        assertFalse(key in healthyKeys(keys))
        timeSource += 1.milliseconds
        assertTrue(key in healthyKeys(keys))
    }

    // Each keyed request outlives the previous key's 30 s ban, so the bans never overlap.
    private fun failSlowlyOnKeys(): AtomicInteger {
        val attempts = AtomicInteger()
        mockkStatic(NETWORKING_ROUTER_KT)
        coEvery { postJsonRpc(any(), any()) } coAnswers { callOriginal() }
        coEvery { postJsonRpc(match { it.path.startsWith("/v2/") }, any()) } coAnswers {
            attempts.incrementAndGet()
            yield() // lets withTimeout stop an unbounded retry loop
            timeSource += 31.seconds
            HttpRpcResponse(503, "unavailable", null)
        }
        return attempts
    }

    private fun failTransportFor(path: String, message: String = "connection refused") {
        mockkStatic(NETWORKING_ROUTER_KT)
        coEvery { postJsonRpc(any(), any()) } coAnswers { callOriginal() }
        coEvery {
            postJsonRpc(match { it.path.endsWith("/$path") }, any())
        } throws IOException(message)
    }

    // A distinct method per request keeps the public pacer from spacing unrelated calls.
    private fun request(id: String = "1", method: String = "getSlot${methodCounter.incrementAndGet()}") =
        RpcRequest(method = method, id = id)

    private fun result(value: Int, id: String = "1") = "{\"jsonrpc\":\"2.0\",\"result\":$value,\"id\":\"$id\"}"

    private fun nodeError(code: Int, message: String, id: String = "1") =
        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":$code,\"message\":\"$message\"},\"id\":\"$id\"}"

    private class RpcServer : AutoCloseable {
        private data class Reply(val status: Int, val body: String, val retryAfter: Long?)

        private val replies = ConcurrentHashMap<String, Reply>()
        val hits = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()

        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                hits.add(path)
                bodies.add(exchange.requestBody.readBytes().decodeToString())
                val reply = replies[path.substringAfterLast('/')] ?: Reply(404, "no reply", null)
                reply.retryAfter?.let { exchange.responseHeaders.add("Retry-After", it.toString()) }
                val bytes = reply.body.encodeToByteArray()
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        private val base = "http://${server.address.hostString}:${server.address.port}"
        val alchemy = AlchemyEndpoint("$base/v2/")

        fun url(path: String) = URL("$base/$path")

        fun reply(path: String, status: Int, body: String, retryAfter: Long? = null) {
            replies[path] = Reply(status, body, retryAfter)
        }

        override fun close() = server.stop(0)
    }

    private object ThrowingSerializer : KSerializer<Int> {
        override val descriptor = PrimitiveSerialDescriptor("Throwing", PrimitiveKind.INT)
        override fun deserialize(decoder: Decoder): Int = throw NoSuchElementException("custom serializer")
        override fun serialize(encoder: Encoder, value: Int) = Unit
    }

    private companion object {
        const val NETWORKING_ROUTER_KT = "com.solana.networking.NetworkingRouterKt"
        const val PUBLIC = "public"
        const val KEY_A = "keyA"
        const val KEY_B = "keyB"
        const val SENTINEL = "SENTINELkey0123456789"
    }
}
