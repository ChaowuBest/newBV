package dev.frost819.newbv.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 播放线路纯函数的单元测试：官方节点判定、过期时间解析、展示文案。
 */
class CdnLinesTest {
    @Test
    fun `isOfficialCdnUrl accepts mirror hosts`() {
        assertThat(isOfficialCdnUrl("https://upos-sz-mirrorcos.bilivideo.com/a/b.m4s?x=1")).isTrue()
    }

    @Test
    fun `isOfficialCdnUrl rejects pcdn and bare ip`() {
        assertThat(isOfficialCdnUrl("https://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/a.m4s")).isFalse()
        assertThat(isOfficialCdnUrl("https://a.szbdyd.com/a.m4s")).isFalse()
        assertThat(isOfficialCdnUrl("http://1.2.3.4:8080/a.m4s?x=1")).isFalse()
    }

    @Test
    fun `playUrlDeadlineSeconds parses deadline wherever it sits`() {
        assertThat(playUrlDeadlineSeconds("https://h/a.m4s?deadline=1700000000&x=1")).isEqualTo(1_700_000_000L)
        assertThat(playUrlDeadlineSeconds("https://h/a.m4s?x=1&deadline=1700000000")).isEqualTo(1_700_000_000L)
    }

    @Test
    fun `playUrlDeadlineSeconds returns null without deadline`() {
        assertThat(playUrlDeadlineSeconds("https://h/a.m4s?x=1")).isNull()
        // 只匹配完整参数名
        assertThat(playUrlDeadlineSeconds("https://h/a.m4s?nodeadline=5")).isNull()
    }

    @Test
    fun `cdnHostOf returns host and falls back to raw string`() {
        assertThat(cdnHostOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s"))
            .isEqualTo("upos-sz-mirrorcos.bilivideo.com")
        assertThat(cdnHostOf("not a url")).isEqualTo("not a url")
    }

    @Test
    fun `cdnDisplayName strips uninformative prefix and suffix`() {
        assertThat(cdnDisplayName("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")).isEqualTo("cos")
        assertThat(cdnDisplayName("https://upos-sz-estgoss.bilivideo.com/a.m4s")).isEqualTo("estgoss")
        assertThat(cdnDisplayName("https://cn-gdgz-cm-01-07.bilivideo.com/a.m4s")).isEqualTo("cn-gdgz-cm-0")
    }

    @Test
    fun `cdnDisplayName labels pcdn and ip`() {
        assertThat(cdnDisplayName("https://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/a.m4s")).isEqualTo("PCDN")
        assertThat(cdnDisplayName("http://1.2.3.4/a.m4s")).isEqualTo("IP")
    }

    @Test
    fun `formatCdnSpeed covers unknown failed and both units`() {
        assertThat(formatCdnSpeed(-1L)).isNull()
        assertThat(formatCdnSpeed(0L)).isEqualTo("异常")
        assertThat(formatCdnSpeed(512L)).isEqualTo("1K/s")
        assertThat(formatCdnSpeed(860L * 1024)).isEqualTo("860K/s")
        assertThat(formatCdnSpeed(5L * 1024 * 1024 + 256 * 1024)).isEqualTo("5.3M/s")
    }

    @Test
    fun `cdnLineLabel omits speed when unknown`() {
        val url = "https://upos-sz-mirrorcos.bilivideo.com/a.m4s"

        assertThat(cdnLineLabel(0, url, -1L)).isEqualTo("线路1 cos")
        assertThat(cdnLineLabel(1, url, 2L * 1024 * 1024)).isEqualTo("线路2 2.0M/s cos")
    }
}
