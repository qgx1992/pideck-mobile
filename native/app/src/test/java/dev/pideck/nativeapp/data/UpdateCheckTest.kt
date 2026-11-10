package dev.pideck.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检查更新」里那些不该出错的判断：tag 怎么剥、怎么比大小、回包怎么读。
 *
 * 这些是唯一会影响「提不提示有新版本」的地方 —— 比错了要么天天弹、要么永远不弹，
 * 而这两种错都不会崩、只会让用户以为功能是坏的，所以逐条钉死。
 */
class UpdateCheckTest {

    @Test
    fun `tag 前缀 v 与大写都剥掉`() {
        assertEquals("0.5.50", UpdateCheck.normalizeVersion("v0.5.50"))
        assertEquals("0.5.50", UpdateCheck.normalizeVersion("V0.5.50"))
        assertEquals("0.5.50", UpdateCheck.normalizeVersion("  0.5.50  "))
        // 不是前缀、只在中间出现的 v 不能动
        assertEquals("0.5.50-dev", UpdateCheck.normalizeVersion("v0.5.50-dev"))
    }

    @Test
    fun `versionCode 跟出包公式一致`() {
        assertEquals(550, UpdateCheck.versionCodeOf("0.5.50"))
        assertEquals(549, UpdateCheck.versionCodeOf("v0.5.49"))
        assertEquals(200, UpdateCheck.versionCodeOf("0.2.0"))
        assertNull(UpdateCheck.versionCodeOf("nightly"))
        assertNull(UpdateCheck.versionCodeOf(""))
    }

    @Test
    fun `比大小按段走，不被 versionCode 公式带偏`() {
        assertTrue(UpdateCheck.isNewer("0.5.50", "0.5.49"))
        assertFalse(UpdateCheck.isNewer("0.5.49", "0.5.50"))
        assertFalse(UpdateCheck.isNewer("0.5.49", "0.5.49"))
        // patch 跨 99：公式会算出和 0.6.0 一样的 600，这里必须仍然更大
        assertTrue(UpdateCheck.isNewer("0.5.100", "0.5.99"))
        assertTrue(UpdateCheck.isNewer("0.6.0", "0.5.100"))
        // 段数不齐：0.5 当作 0.5.0
        assertFalse(UpdateCheck.isNewer("0.5", "0.5.0"))
        assertTrue(UpdateCheck.isNewer("0.5.1", "0.5"))
        // 解析不出来的 tag 一律当「不是新版」，否则会天天弹提示
        assertFalse(UpdateCheck.isNewer("nightly", "0.5.49"))
        assertFalse(UpdateCheck.isNewer("0.5.50", "本地"))
    }

    @Test
    fun `回包里取 tag、页面、APK 直链`() {
        val json = """
            {
              "tag_name": "v0.5.50",
              "html_url": "https://github.com/qgx1992/pideck-mobile/releases/tag/v0.5.50",
              "published_at": "2026-11-10T08:00:00Z",
              "body": "0.5.50 说明",
              "draft": false,
              "assets": [
                {"name": "SHA256SUMS.txt", "browser_download_url": "https://example.com/sums", "size": 92},
                {"name": "PiDeck-0.5.50-release.apk", "browser_download_url": "https://example.com/app.apk", "size": 15517964}
              ]
            }
        """.trimIndent()
        val release = UpdateCheck.parseLatestRelease(json)!!
        assertEquals("0.5.50", release.versionName)
        assertEquals(550, release.versionCode)
        assertEquals("https://example.com/app.apk", release.apkUrl)
        assertEquals(15517964L, release.apkSize)
        assertEquals("0.5.50 说明", release.notes)
        assertTrue(release.pageUrl.endsWith("v0.5.50"))
    }

    @Test
    fun `没有 apk 附件时直链为 null，页面照样给`() {
        val json = """
            {"tag_name": "v0.5.50", "html_url": "https://example.com/rel", "assets": []}
        """.trimIndent()
        val release = UpdateCheck.parseLatestRelease(json)!!
        assertNull(release.apkUrl)
        assertNull(release.apkSize)
        assertEquals("https://example.com/rel", release.pageUrl)
    }

    @Test
    fun `坏回包与草稿都不算新版本`() {
        assertNull(UpdateCheck.parseLatestRelease("not json"))
        assertNull(UpdateCheck.parseLatestRelease("""{"message": "Not Found"}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{"tag_name": "v0.5.50", "draft": true}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{"tag_name": "latest"}"""))
        assertNull(UpdateCheck.parseLatestRelease("""{}"""))
    }

    @Test
    fun `状态文案说清有没有新版`() {
        val release = RemoteRelease("0.5.50", 550, "https://example.com/rel")
        val available = UpdateCenter.describeState(UpdateState.Available(release))
        assertTrue(available, available.contains("0.5.50"))
        assertTrue(UpdateCenter.describeState(UpdateState.Checking).contains("正在检查"))
        assertTrue(
            UpdateCenter.describeState(UpdateState.Failed("连不上")).contains("没查成"),
        )
    }
}
