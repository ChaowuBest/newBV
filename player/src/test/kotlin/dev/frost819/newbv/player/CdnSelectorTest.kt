package dev.frost819.newbv.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OkHttpCdnSelector] 与 [orderCdnCandidates] 的单元测试。
 *
 * 测速通过 OkHttp 拦截器伪造响应（不走真实网络）：按 host 返回「快 / 慢 / 失败」三种结果，
 * 覆盖排序分档、缓存与后台重测、故障惩罚等语义。
 */
class CdnSelectorTest {
    private val fast = "https://fast.example/v.m4s"
    private val slow = "https://slow.example/v.m4s"
    private val dead = "https://dead.example/v.m4s"

    /** 各 host 被请求的次数。 */
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    private var now = 1_000_000L

    /**
     * 伪造 CDN：`fast` 立即返回 256KB；`slow` 每次读取前等待，吞吐远低于 `fast`；`dead` 连接失败。
     */
    private val fakeCdn =
        Interceptor { chain ->
            val host = chain.request().url.host
            hits.getOrPut(host) { AtomicInteger() }.incrementAndGet()
            val body: ResponseBody =
                when (host) {
                    "fast.example" -> ByteArray(FAST_BODY_BYTES).toResponseBody()
                    "slow.example" -> SlowBody(ByteArray(SLOW_BODY_BYTES), delayMs = SLOW_READ_DELAY_MS)
                    else -> throw IOException("connection refused")
                }
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(206)
                .message("Partial Content")
                .body(body)
                .build()
        }

    private fun selector() =
        OkHttpCdnSelector(
            client = OkHttpClient.Builder().addInterceptor(fakeCdn).build(),
            clock = { now },
        )

    private fun hitsOf(host: String) = hits[host]?.get() ?: 0

    // ── rank：输入处理 ───────────────────────────────────────

    @Test
    fun `rank empty returns empty`() =
        runTest {
            assertThat(selector().rank(emptyList())).isEmpty()
        }

    @Test
    fun `rank single url short-circuits without probing`() =
        runTest {
            assertThat(selector().rank(listOf(fast))).containsExactly(fast)
            assertThat(hitsOf("fast.example")).isEqualTo(0)
        }

    @Test
    fun `rank filters blank and duplicate urls`() =
        runTest {
            assertThat(selector().rank(listOf("", "  ", fast, fast))).containsExactly(fast)
        }

    // ── rank：测速排序 ───────────────────────────────────────

    @Test
    fun `rank puts faster host first and failed host last`() =
        runTest {
            val ranked = selector().rank(listOf(dead, slow, fast))

            assertThat(ranked).containsExactly(fast, slow, dead).inOrder()
        }

    @Test
    fun `rank preserves input order when all hosts are unreachable`() =
        runTest {
            val first = "https://dead.example/first.m4s"
            val second = "https://dead2.example/second.m4s"

            assertThat(selector().rank(listOf(first, second))).containsExactly(first, second).inOrder()
        }

    @Test
    fun `rank probes each host only once for several urls`() =
        runTest {
            val backup = "https://fast.example/backup.m4s"

            selector().rank(listOf(fast, backup, slow))

            assertThat(hitsOf("fast.example")).isEqualTo(1)
        }

    // ── rank：缓存 ───────────────────────────────────────────

    @Test
    fun `rank reuses fresh result without probing again`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, slow))
            now += OkHttpCdnSelector.DEFAULT_FRESH_TTL_MS - 1

            selector.rank(listOf(fast, slow))

            assertThat(hitsOf("fast.example")).isEqualTo(1)
            assertThat(hitsOf("slow.example")).isEqualTo(1)
        }

    @Test
    fun `rank probes again once result is past its stale ttl`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, slow))
            now += OkHttpCdnSelector.DEFAULT_STALE_TTL_MS + 1

            selector.rank(listOf(fast, slow))

            assertThat(hitsOf("fast.example")).isEqualTo(2)
        }

    @Test
    fun `rank does not wait on failed host again within failure ttl`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, dead))
            now += OkHttpCdnSelector.DEFAULT_FAILURE_TTL_MS - 1

            selector.rank(listOf(fast, dead))

            assertThat(hitsOf("dead.example")).isEqualTo(1)
        }

    // ── 故障上报 ─────────────────────────────────────────────

    @Test
    fun `reportFailure moves host to the end and skips probing it`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, slow))

            selector.reportFailure(fast)
            val ranked = selector.rank(listOf(fast, slow))

            assertThat(ranked).containsExactly(slow, fast).inOrder()
            assertThat(selector.speedOf(fast)).isEqualTo(0L)
            // 惩罚期内不重测
            assertThat(hitsOf("fast.example")).isEqualTo(1)
        }

    @Test
    fun `penalty expires and host is probed again`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, slow))
            selector.reportFailure(fast)
            now += OkHttpCdnSelector.DEFAULT_FAILURE_PENALTY_MS + 1

            val ranked = selector.rank(listOf(slow, fast))

            assertThat(ranked.first()).isEqualTo(fast)
            assertThat(hitsOf("fast.example")).isEqualTo(2)
        }

    @Test
    fun `reportSlow penalty is shorter than failure penalty`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, slow))
            selector.reportSlow(fast)
            now += OkHttpCdnSelector.DEFAULT_SLOW_PENALTY_MS + 1

            assertThat(selector.rank(listOf(slow, fast)).first()).isEqualTo(fast)
        }

    // ── deprioritizeFailed ──────────────────────────────────

    @Test
    fun `deprioritizeFailed keeps input order and makes no request`() {
        val selector = selector()

        assertThat(selector.deprioritizeFailed(listOf(slow, fast, dead)))
            .containsExactly(slow, fast, dead)
            .inOrder()
        assertThat(hits).isEmpty()
    }

    @Test
    fun `deprioritizeFailed moves reported host to the end`() {
        val selector = selector()
        selector.reportFailure(slow)

        assertThat(selector.deprioritizeFailed(listOf(slow, fast, dead)))
            .containsExactly(fast, dead, slow)
            .inOrder()
    }

    // ── speedOf ─────────────────────────────────────────────

    @Test
    fun `speedOf returns minus one for unknown host`() {
        assertThat(selector().speedOf(fast)).isEqualTo(-1L)
    }

    @Test
    fun `speedOf returns measured throughput and zero for failed host`() =
        runTest {
            val selector = selector()
            selector.rank(listOf(fast, dead))

            assertThat(selector.speedOf(fast)).isGreaterThan(0L)
            assertThat(selector.speedOf(dead)).isEqualTo(0L)
        }

    // ── orderCdnCandidates（纯函数）──────────────────────────

    private fun order(
        urls: List<String>,
        states: Map<String, CdnHostState>,
    ) = orderCdnCandidates(urls, { states[it] }, nowMs = 100L)

    private fun measured(speed: Long) = CdnHostState(bytesPerSecond = speed, measuredAtMs = 50L)

    @Test
    fun `order keeps input order among hosts within the same tier`() {
        // b 比 a 快不到 25%：视为同一档，尊重接口顺序
        val ordered = order(listOf("a", "b"), mapOf("a" to measured(900), "b" to measured(1000)))

        assertThat(ordered).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `order sorts clearly slower hosts by throughput`() {
        val ordered =
            order(
                listOf("slowest", "slower", "best"),
                mapOf("slowest" to measured(100), "slower" to measured(400), "best" to measured(1000)),
            )

        assertThat(ordered).containsExactly("best", "slower", "slowest").inOrder()
    }

    @Test
    fun `order puts unknown then failed then penalized after measured hosts`() {
        val ordered =
            order(
                listOf("penalized", "failed", "unknown", "ok"),
                mapOf(
                    "penalized" to CdnHostState(bytesPerSecond = 5000, measuredAtMs = 50L, penalizedUntilMs = 200L),
                    "failed" to measured(0),
                    "ok" to measured(1000),
                ),
            )

        assertThat(ordered).containsExactly("ok", "unknown", "failed", "penalized").inOrder()
    }

    @Test
    fun `order ignores expired penalty`() {
        val ordered =
            order(
                listOf("recovered", "ok"),
                mapOf(
                    "recovered" to CdnHostState(bytesPerSecond = 1000, measuredAtMs = 50L, penalizedUntilMs = 99L),
                    "ok" to measured(1000),
                ),
            )

        assertThat(ordered).containsExactly("recovered", "ok").inOrder()
    }

    /** 每次读取前等待的响应体，用来模拟吞吐低的节点。 */
    private class SlowBody(
        private val bytes: ByteArray,
        private val delayMs: Long,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = bytes.size.toLong()

        override fun source(): BufferedSource =
            object : ForwardingSource(Buffer().write(bytes)) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    Thread.sleep(delayMs)
                    return super.read(sink, byteCount)
                }
            }.buffer()
    }

    private companion object {
        const val FAST_BODY_BYTES = 256 * 1024
        const val SLOW_BODY_BYTES = 32 * 1024
        const val SLOW_READ_DELAY_MS = 40L
    }
}
