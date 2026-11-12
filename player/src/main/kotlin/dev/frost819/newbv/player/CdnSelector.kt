package dev.frost819.newbv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * CDN 地址选择器。
 *
 * 负责对候选播放地址测速、记忆节点好坏并给出推荐顺序，供播放器起播前选择最优节点，
 * 以及在播放中途出错 / 卡死时把故障节点排到最后。
 */
interface CdnSelector {
    /**
     * 对候选地址测速并按推荐顺序排序。
     *
     * - 测速结果按 host 缓存：新鲜期内直接使用；过了新鲜期但仍在保质期内时先用旧结果、
     *   后台静默重测（下次播放生效），不增加起播等待；
     * - 没有可用缓存的 host 并发做小流量测速（阻塞本次调用，有超时上限）；
     * - 吞吐接近最优者（同一档）保持入参顺序，避免测速抖动造成无意义的换源；
     * - 测速失败、被 [reportFailure] / [reportSlow] 标记的 host 排在最后。
     *
     * @param urls 候选播放地址（同一视频/音频轨道的 base + backup 列表）。
     * @return 排序后的地址列表；入参为空时返回空列表。
     */
    suspend fun rank(urls: List<String>): List<String>

    /**
     * 不发起任何网络请求，仅依据已知的故障标记调整顺序：被标记的 host 排到最后，
     * 其余保持入参顺序。用于未开启测速选源时的失败回退。
     *
     * @param urls 候选播放地址。
     * @return 调整顺序后的地址列表（已去重、去空白）。
     */
    fun deprioritizeFailed(urls: List<String>): List<String>

    /**
     * 上报某地址在播放中失败（连接失败 / HTTP 错误 / 读超时等）。
     *
     * 对应 host 在惩罚期内排到候选末尾，且缓存的测速结果作废。
     */
    fun reportFailure(url: String)

    /**
     * 上报某地址在播放中吞吐塌陷（长时间缓冲且无进展）。
     *
     * 惩罚比 [reportFailure] 轻：节点可能只是暂时拥塞。
     */
    fun reportSlow(url: String)

    /**
     * 查询某地址所在 host 的最近一次测速吞吐。
     *
     * @return 字节/秒；`0` 表示测速失败或处于惩罚期；`-1` 表示尚无数据。
     */
    fun speedOf(url: String): Long
}

/** 单个 host 的测速 / 故障状态。 */
internal data class CdnHostState(
    /** 最近一次测速吞吐（字节/秒）；0 表示测速失败。 */
    val bytesPerSecond: Long,
    /** 测速完成时间（毫秒时间戳）；0 表示从未测速。 */
    val measuredAtMs: Long,
    /** 惩罚截止时间（毫秒时间戳）；不大于当前时间表示未被惩罚。 */
    val penalizedUntilMs: Long = 0L,
)

/**
 * 按测速结果给候选地址排序（纯函数，便于单测）。
 *
 * 排序分档（档内保持入参顺序）：
 * 1. 吞吐不低于最优者 [SAME_TIER_RATIO] 的地址——视为同样好，尊重接口返回的顺序；
 * 2. 其余测得吞吐的地址，按吞吐从高到低；
 * 3. 尚无测速数据的地址；
 * 4. 测速失败的地址；
 * 5. 处于惩罚期的地址。
 *
 * @param urls 候选地址（已去重）。
 * @param stateOf 查询地址所在 host 状态的函数；无记录返回 null。
 * @param nowMs 当前时间（毫秒时间戳）。
 */
internal fun orderCdnCandidates(
    urls: List<String>,
    stateOf: (String) -> CdnHostState?,
    nowMs: Long,
): List<String> {
    data class Entry(
        val url: String,
        val index: Int,
        val tier: Int,
        val speed: Long,
    )

    val speeds =
        urls.map { url ->
            val state = stateOf(url)
            when {
                state == null -> SPEED_UNKNOWN
                state.penalizedUntilMs > nowMs -> SPEED_PENALIZED
                state.measuredAtMs <= 0L -> SPEED_UNKNOWN
                else -> state.bytesPerSecond
            }
        }
    val best = speeds.filter { it > 0 }.maxOrNull() ?: 0L
    val entries =
        urls.mapIndexed { index, url ->
            val speed = speeds[index]
            val tier =
                when {
                    speed == SPEED_PENALIZED -> TIER_PENALIZED
                    speed == SPEED_UNKNOWN -> TIER_UNKNOWN
                    speed <= 0L -> TIER_FAILED
                    speed >= best * SAME_TIER_RATIO -> TIER_BEST
                    else -> TIER_MEASURED
                }
            Entry(url, index, tier, speed)
        }
    return entries
        .sortedWith(
            compareBy<Entry> { it.tier }
                // 只有「明显更慢」的那一档按吞吐排序，其余档保持入参顺序
                .thenByDescending { if (it.tier == TIER_MEASURED) it.speed else 0L }
                .thenBy { it.index },
        ).map { it.url }
}

private const val SPEED_UNKNOWN = -1L
private const val SPEED_PENALIZED = -2L

private const val TIER_BEST = 0
private const val TIER_MEASURED = 1
private const val TIER_UNKNOWN = 2
private const val TIER_FAILED = 3
private const val TIER_PENALIZED = 4

/** 吞吐达到最优者的该比例即视为同一档（并发测速互相抢带宽，差距在此之内没有区分意义）。 */
private const val SAME_TIER_RATIO = 0.75

/**
 * 基于 OkHttp Range 请求的 CDN 测速选择器。
 *
 * 测速方式：对每个候选地址发起 `Range: bytes=0-<sampleBytes-1>` 请求，从收到响应头开始计时，
 * 在 [measureWindowMs] 的时间窗内尽量多读（最多读满样本），换算为吞吐（字节/秒）。
 * 建连 / 握手耗时不计入吞吐——比较的是「下载速度」而不只是「延迟」；但整个请求受
 * [measureTimeoutMs] 约束，握手过慢或中途断流的节点会被判为失败。
 *
 * 测速复用播放器的连接池：测速胜出的节点连接已建立，起播时可直接复用（相当于预热）。
 *
 * @param client 复用的 OkHttpClient，内部会派生一个短超时的测速专用 client（共享连接池）。
 * @param headersProvider 测速请求头（User-Agent / Referer），应与播放器实际拉流一致，
 *   否则部分节点对测速和拉流的响应不同。
 * @param freshTtlMs 测速结果新鲜期：期内直接使用。
 * @param staleTtlMs 测速结果保质期：超过新鲜期但未过保质期时先用旧结果、后台重测。
 * @param failureTtlMs 测速失败结果的缓存时长（避免每次起播都等坏节点超时）。
 * @param failurePenaltyMs 播放中失败的节点的惩罚时长。
 * @param slowPenaltyMs 播放中吞吐塌陷的节点的惩罚时长。
 * @param sampleBytes 每次测速最多读取的样本字节数。
 * @param measureWindowMs 吞吐测量时间窗（从收到响应头起算）。
 * @param measureTimeoutMs 单个地址测速的总超时（含建连 / 握手）。
 * @param clock 当前时间来源（毫秒时间戳），便于单测。
 * @param refreshScope 后台重测使用的协程作用域。
 */
@Suppress("LongParameterList")
class OkHttpCdnSelector(
    client: OkHttpClient,
    private val headersProvider: () -> Map<String, String> = { DEFAULT_HEADERS },
    private val freshTtlMs: Long = DEFAULT_FRESH_TTL_MS,
    private val staleTtlMs: Long = DEFAULT_STALE_TTL_MS,
    private val failureTtlMs: Long = DEFAULT_FAILURE_TTL_MS,
    private val failurePenaltyMs: Long = DEFAULT_FAILURE_PENALTY_MS,
    private val slowPenaltyMs: Long = DEFAULT_SLOW_PENALTY_MS,
    private val sampleBytes: Long = DEFAULT_SAMPLE_BYTES,
    private val measureWindowMs: Long = DEFAULT_MEASURE_WINDOW_MS,
    measureTimeoutMs: Long = DEFAULT_MEASURE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CdnSelector {
    /** host → 状态。读写都在 [lock] 内（不用 `ConcurrentHashMap.compute`：它要求 API 24，而 minSdk 为 21）。 */
    private val hostStates = HashMap<String, CdnHostState>()

    /** 正在后台重测的 host，避免同一 host 重复发起。同样受 [lock] 保护。 */
    private val refreshingHosts = HashSet<String>()

    private val lock = Any()

    private val measureClient =
        client
            .newBuilder()
            .connectTimeout(measureTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(measureTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(measureTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    override suspend fun rank(urls: List<String>): List<String> {
        val candidates = normalize(urls)
        if (candidates.size <= 1) return candidates

        val now = clock()
        // 同一 host 只测一次：取该 host 的第一个地址作代表
        val byHost = candidates.groupBy { hostOf(it) }.mapValues { it.value.first() }

        val needProbe = mutableListOf<String>()
        val needRefresh = mutableListOf<String>()
        for ((host, url) in byHost) {
            when (probeActionFor(stateOfHost(host), now)) {
                ProbeAction.Probe -> needProbe += url
                ProbeAction.Refresh -> needRefresh += url
                ProbeAction.None -> Unit
            }
        }

        if (needProbe.isNotEmpty()) probe(needProbe)
        if (needRefresh.isNotEmpty()) refreshInBackground(needRefresh)

        return orderCdnCandidates(candidates, { stateOfHost(hostOf(it)) }, clock())
    }

    /** 判断某 host 本次排序前需要做什么。 */
    private fun probeActionFor(
        state: CdnHostState?,
        now: Long,
    ): ProbeAction {
        if (state == null) return ProbeAction.Probe
        // 惩罚期内不再测速：既不浪费起播时间，也不让它凭一次小样本翻身
        if (state.penalizedUntilMs > now) return ProbeAction.None
        if (state.measuredAtMs <= 0L) return ProbeAction.Probe
        val age = now - state.measuredAtMs
        return when {
            state.bytesPerSecond <= 0L -> if (age > failureTtlMs) ProbeAction.Probe else ProbeAction.None
            age > staleTtlMs -> ProbeAction.Probe
            age > freshTtlMs -> ProbeAction.Refresh
            else -> ProbeAction.None
        }
    }

    private fun stateOfHost(host: String): CdnHostState? = synchronized(lock) { hostStates[host] }

    override fun deprioritizeFailed(urls: List<String>): List<String> {
        val candidates = normalize(urls)
        if (candidates.size <= 1) return candidates
        val now = clock()
        // 稳定排序：未被惩罚的保持入参顺序在前，被惩罚的保持相对顺序在后
        return candidates.sortedBy { url ->
            val state = stateOfHost(hostOf(url))
            if (state != null && state.penalizedUntilMs > now) 1 else 0
        }
    }

    override fun reportFailure(url: String) = penalize(url, failurePenaltyMs)

    override fun reportSlow(url: String) = penalize(url, slowPenaltyMs)

    override fun speedOf(url: String): Long {
        val state = stateOfHost(hostOf(url)) ?: return -1L
        return when {
            state.penalizedUntilMs > clock() -> 0L
            state.measuredAtMs <= 0L -> -1L
            else -> state.bytesPerSecond
        }
    }

    private fun penalize(
        url: String,
        durationMs: Long,
    ) {
        if (url.isBlank()) return
        val until = clock() + durationMs
        val host = hostOf(url)
        synchronized(lock) {
            // 惩罚结束后旧测速结果已不可信，置为「从未测速」，届时重新测
            hostStates[host] =
                CdnHostState(
                    bytesPerSecond = 0L,
                    measuredAtMs = 0L,
                    penalizedUntilMs = maxOf(until, hostStates[host]?.penalizedUntilMs ?: 0L),
                )
        }
    }

    /** 并发测速并写入缓存（阻塞至全部完成，单个受测速超时约束）。 */
    private suspend fun probe(urls: List<String>) {
        coroutineScope {
            urls
                .map { url -> async(Dispatchers.IO) { url to runInterruptible { measure(url) } } }
                .awaitAll()
        }.forEach { (url, speed) -> store(url, speed) }
    }

    /** 后台重测：不阻塞本次起播，结果供下次排序使用。 */
    private fun refreshInBackground(urls: List<String>) {
        urls.forEach { url ->
            val host = hostOf(url)
            val started = synchronized(lock) { refreshingHosts.add(host) }
            if (!started) return@forEach
            refreshScope.launch {
                try {
                    store(url, runInterruptible { measure(url) })
                } finally {
                    synchronized(lock) { refreshingHosts.remove(host) }
                }
            }
        }
    }

    private fun store(
        url: String,
        speed: Long,
    ) {
        val now = clock()
        val host = hostOf(url)
        synchronized(lock) {
            hostStates[host] =
                CdnHostState(
                    bytesPerSecond = speed,
                    measuredAtMs = now,
                    // 测速不解除播放中上报的惩罚
                    penalizedUntilMs = hostStates[host]?.penalizedUntilMs ?: 0L,
                )
        }
    }

    /**
     * 对单个地址做 Range 测速，返回吞吐（字节/秒）；失败返回 0。
     *
     * 响应头到达后才开始计时；读满样本或时间窗耗尽即停止。中途断流但已读到数据时，
     * 按已读部分折算（断流本身会拉低结果），一个字节都没读到才算失败。
     */
    private fun measure(url: String): Long {
        val request =
            try {
                Request
                    .Builder()
                    .url(url)
                    .header("Range", "bytes=0-${sampleBytes - 1}")
                    .apply { headersProvider().forEach { (name, value) -> header(name, value) } }
                    .build()
            } catch (e: IllegalArgumentException) {
                return 0L
            }
        return try {
            measureClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return 0L
                val source = response.body?.source() ?: return 0L
                val buffer = Buffer()
                val windowNs = TimeUnit.MILLISECONDS.toNanos(measureWindowMs)
                val bodyStartNs = System.nanoTime()
                var total = 0L
                try {
                    while (total < sampleBytes && System.nanoTime() - bodyStartNs < windowNs) {
                        val read = source.read(buffer, minOf(READ_CHUNK_BYTES, sampleBytes - total))
                        if (read == -1L) break
                        total += read
                        buffer.clear()
                    }
                } catch (e: IOException) {
                    if (total <= 0L) return 0L
                }
                val elapsedNs = (System.nanoTime() - bodyStartNs).coerceAtLeast(MIN_ELAPSED_NS)
                if (total <= 0L) 0L else total * NANOS_PER_SECOND / elapsedNs
            }
        } catch (e: IOException) {
            0L
        }
    }

    private fun normalize(urls: List<String>): List<String> = urls.filter { it.isNotBlank() }.distinct()

    /** 排序前对单个 host 要做的动作。 */
    private enum class ProbeAction {
        /** 阻塞测速（没有可用缓存）。 */
        Probe,

        /** 后台重测（缓存过了新鲜期但仍可用）。 */
        Refresh,

        /** 无需测速。 */
        None,
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)

    companion object {
        /** 测速结果新鲜期：3 分钟。 */
        const val DEFAULT_FRESH_TTL_MS = 3 * 60 * 1000L

        /** 测速结果保质期：15 分钟（新鲜期到保质期之间先用旧结果、后台重测）。 */
        const val DEFAULT_STALE_TTL_MS = 15 * 60 * 1000L

        /** 测速失败结果的缓存时长：2 分钟。 */
        const val DEFAULT_FAILURE_TTL_MS = 2 * 60 * 1000L

        /** 播放中失败的节点惩罚时长：10 分钟。 */
        const val DEFAULT_FAILURE_PENALTY_MS = 10 * 60 * 1000L

        /** 播放中吞吐塌陷的节点惩罚时长：3 分钟。 */
        const val DEFAULT_SLOW_PENALTY_MS = 3 * 60 * 1000L

        /** 默认样本字节数：1 MiB。 */
        const val DEFAULT_SAMPLE_BYTES = 1024L * 1024

        /** 默认吞吐测量时间窗：1.2 秒。 */
        const val DEFAULT_MEASURE_WINDOW_MS = 1_200L

        /** 默认单地址测速总超时：3 秒。 */
        const val DEFAULT_MEASURE_TIMEOUT_MS = 3_000L

        private const val READ_CHUNK_BYTES = 16L * 1024
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** 计时下限（1 毫秒）：极小样本瞬间读完时避免除以近零。 */
        private const val MIN_ELAPSED_NS = 1_000_000L

        private val DEFAULT_HEADERS =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "https://www.bilibili.com",
            )
    }
}
