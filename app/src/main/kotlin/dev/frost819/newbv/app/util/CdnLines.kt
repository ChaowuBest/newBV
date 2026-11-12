package dev.frost819.newbv.app.util

import java.net.URI
import java.util.Locale

/*
 * 播放线路（CDN 候选地址）相关的纯函数：官方节点判定、过期时间解析、菜单展示文案。
 */

/** 形如 `http(s)://1.2.3.4(:port)/...` 的裸 IP 地址。 */
private val IP_URL_REGEX =
    Regex("^(https?://)?(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?)(/.*)?(\\?.*)?$")

/** 播放地址中的过期时间参数（秒级时间戳）。 */
private val DEADLINE_REGEX = Regex("[?&]deadline=(\\d+)")

/** 线路名里没有信息量的域名后缀。 */
private val CDN_HOST_SUFFIXES = listOf(".bilivideo.com", ".bilivideo.cn", ".akamaized.net")

/** 线路名里没有信息量的主机名前缀（按顺序依次剥离）。 */
private val CDN_HOST_PREFIXES = listOf("upos-sz-", "upos-hz-", "upos-", "mirror")

/** 线路名展示的最大长度（菜单项宽度有限，更长的截断）。 */
private const val CDN_NAME_MAX_LENGTH = 12

private const val BYTES_PER_MB = 1024 * 1024
private const val BYTES_PER_KB = 1024

/**
 * 是否为官方 CDN 地址。
 *
 * PCDN（`mcdn` / `szbdyd`）与裸 IP 地址稳定性差，不算官方节点：只在官方节点全部不可用时兜底。
 */
fun isOfficialCdnUrl(url: String): Boolean =
    !url.contains(".mcdn.bilivideo.") &&
        !url.contains(".szbdyd.com") &&
        !IP_URL_REGEX.matches(url)

/**
 * 解析播放地址的过期时间。
 *
 * @return `deadline` 参数（秒级时间戳）；地址不带该参数时返回 null
 */
fun playUrlDeadlineSeconds(url: String): Long? =
    DEADLINE_REGEX
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

/** 取地址的 host；解析失败时原样返回，保证同一地址总得到同一结果。 */
fun cdnHostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url

/**
 * 线路的简短名称（去掉无信息量的前后缀，如 `upos-sz-mirrorcos.bilivideo.com` → `cos`）。
 */
fun cdnDisplayName(url: String): String {
    if (url.contains(".mcdn.bilivideo.") || url.contains(".szbdyd.com")) return "PCDN"
    if (IP_URL_REGEX.matches(url)) return "IP"
    var name = cdnHostOf(url)
    CDN_HOST_SUFFIXES.firstOrNull { name.endsWith(it) }?.let { name = name.removeSuffix(it) }
    CDN_HOST_PREFIXES.forEach { prefix ->
        // 剥到只剩空串就不剥了（如主机名恰好就叫 mirror）
        if (name.startsWith(prefix) && name.length > prefix.length) name = name.removePrefix(prefix)
    }
    return name.take(CDN_NAME_MAX_LENGTH)
}

/**
 * 测速吞吐的展示文案。
 *
 * @param bytesPerSecond 字节/秒；`0` 表示测速失败或节点被标记故障；负数表示尚无数据
 * @return 如 `5.2M/s`、`860K/s`、`异常`；尚无数据时返回 null
 */
fun formatCdnSpeed(bytesPerSecond: Long): String? =
    when {
        bytesPerSecond < 0L -> null
        bytesPerSecond == 0L -> "异常"
        bytesPerSecond >= BYTES_PER_MB ->
            String.format(Locale.US, "%.1fM/s", bytesPerSecond.toDouble() / BYTES_PER_MB)
        else -> "${(bytesPerSecond / BYTES_PER_KB).coerceAtLeast(1L)}K/s"
    }

/**
 * 播放器菜单里一条线路的展示文案，如 `线路1 5.2M/s cos`。
 *
 * 速度放在名称前面：菜单项宽度有限、超长截断时，优先保住速度。
 *
 * @param index 线路下标（从 0 开始）
 * @param url 线路地址
 * @param bytesPerSecond 最近一次测速吞吐，含义同 [formatCdnSpeed]
 */
fun cdnLineLabel(
    index: Int,
    url: String,
    bytesPerSecond: Long,
): String =
    listOfNotNull(
        "线路${index + 1}",
        formatCdnSpeed(bytesPerSecond),
        cdnDisplayName(url),
    ).joinToString(" ")
