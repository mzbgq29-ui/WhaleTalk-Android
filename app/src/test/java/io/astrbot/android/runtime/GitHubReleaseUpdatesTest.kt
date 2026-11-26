package io.astrbot.android.runtime

import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseUpdatesTest {
    @Test fun comparesDottedVersions() {
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.5.0", "1.4.0") > 0)
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.4.0", "1.5.0") < 0)
        assertEquals(0, GitHubReleaseUpdates.compareVersionNames("v1.5.0", "1.5.0"))
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.5.1", "1.5.0") > 0)
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.10.0", "1.9.0") > 0)
    }

    @Test fun ordersPrereleasesBeforeStableVersions() {
        val versions = listOf("1.8.2-alpha1", "1.8.2-alpha.2", "1.8.2-beta.1", "1.8.2-rc1", "1.8.2-rc10", "1.8.2")
        for ((earlier, later) in versions.zipWithNext()) {
            assertTrue(GitHubReleaseUpdates.compareVersionNames(earlier, later) < 0)
            assertTrue(GitHubReleaseUpdates.compareVersionNames(later, earlier) > 0)
        }
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.8.2-rc1", "1.8.2") < 0)
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.8.3-alpha1", "1.8.2") > 0)
    }

    @Test fun ignoresTrailingZeroComponents() {
        assertEquals(0, GitHubReleaseUpdates.compareVersionNames("1.8.0", "1.8"))
        assertEquals(0, GitHubReleaseUpdates.compareVersionNames("1.8", "1.8.0.0"))
        assertTrue(GitHubReleaseUpdates.compareVersionNames("1.8", "1.8.0-rc1") > 0)
    }

    @Test fun prefersVersionCodeWhenPresent() {
        assertTrue(GitHubReleaseUpdates.isNewer("1.4.0", 100500, "1.5.0", 100400))
        assertFalse(GitHubReleaseUpdates.isNewer("1.6.0", 100300, "1.5.0", 100500))
        assertTrue(GitHubReleaseUpdates.isNewer("1.5.0", null, "1.4.0", 100400))
        assertFalse(GitHubReleaseUpdates.isNewer("1.4.0", null, "1.5.0", 100500))
    }

    @Test fun readsVersionCodeHints() {
        assertEquals(100500L, GitHubReleaseUpdates.versionCodeFromText("管理器版本 1.5.0，版本代码 100500，包名为 dev.whaletalk.android。"))
        assertEquals(100500L, GitHubReleaseUpdates.versionCodeFromText("versionCode: 100500"))
        assertNull(GitHubReleaseUpdates.versionCodeFromText("no code here"))
    }

    @Test fun parsesANewerGithubRelease() {
        val json = """
            [
              {
                "tag_name": "v1.5.0",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/tag/v1.5.0",
                "body": "鲸语 1.5.0，版本代码 100500",
                "assets": [
                  {"name": "WhaleTalk-Android-1.5.0-arm64-v8a.apk", "browser_download_url": "https://example.com/app.apk", "size": 1024},
                  {"name": "source.zip", "browser_download_url": "https://example.com/source.zip", "size": 10}
                ]
              },
              {
                "tag_name": "v1.4.0",
                "draft": false,
                "prerelease": false,
                "html_url": "https://github.com/mzbgq29-ui/WhaleTalk-Android/releases/tag/v1.4.0",
                "body": "1.4.0",
                "assets": []
              }
            ]
        """.trimIndent()
        val check = GitHubReleaseUpdates.parseReleases(json, "1.4.0", 100400)
        assertEquals("1.5.0", check.latest?.versionName)
        assertEquals("1.5.0", check.candidate?.versionName)
        assertEquals(100500L, check.candidate?.versionCode)
        assertEquals("https://example.com/app.apk", check.candidate?.apkUrl)
        assertEquals(1024L, check.candidate?.apkBytes)
        assertTrue(check.message.contains("1.5.0"))
        assertEquals("github-releases", check.source)
    }

    @Test fun skipsDraftsAndTreatsCurrentAsLatest() {
        val json = """
            [
              {"tag_name": "v1.6.0", "draft": true, "prerelease": false, "html_url": "https://example.com/draft", "body": "", "assets": []},
              {"tag_name": "v1.5.0", "draft": false, "prerelease": false, "html_url": "https://example.com/150", "body": "", "assets": []}
            ]
        """.trimIndent()
        val check = GitHubReleaseUpdates.parseReleases(json, "1.5.0", 100500)
        assertEquals("1.5.0", check.latest?.versionName)
        assertNull(check.candidate)
        assertTrue(check.message.contains("已是最新版本"))
    }

    @Test fun parsesTagsWhenReleasesAreMissing() {
        val json = """[{"name": "v1.4.0"}, {"name": "v1.6.0-rc1"}, {"name": "v1.5.0"}]"""
        val check = GitHubReleaseUpdates.parseTags(json, "1.4.0", 100400)
        assertEquals("1.5.0", check.candidate?.versionName)
        assertNull(check.candidate?.apkUrl)
        assertTrue(check.message.contains("尚未提供安装包"))
        assertEquals("github-tags", check.source)
    }

    @Test fun findsTheHighestStableReleaseRegardlessOfApiOrder() {
        val json = """
            [
              {"tag_name": "v1.8.0"},
              {"tag_name": "v2.0.0-rc1", "prerelease": true},
              {"tag_name": "v1.8.2"},
              {"tag_name": "v1.8.1"}
            ]
        """.trimIndent()
        val check = GitHubReleaseUpdates.parseReleases(json, "1.8.1", 100801)
        assertEquals("1.8.2", check.latest?.versionName)
        assertEquals("1.8.2", check.candidate?.versionName)
    }

    @Test fun findsTheHighestPrereleaseWhenThereAreNoStableEntries() {
        val releases = """[{"tag_name":"v1.8.2-beta.1","prerelease":true},{"tag_name":"v1.8.2-rc1","prerelease":true}]"""
        val tags = """[{"name":"v1.8.2-beta.1"},{"name":"v1.8.2-rc1"}]"""
        val releaseCheck = GitHubReleaseUpdates.parseReleases(releases, "1.8.1", 100801)
        val tagCheck = GitHubReleaseUpdates.parseTags(tags, "1.8.1", 100801)
        for (check in listOf(releaseCheck, tagCheck)) {
            assertEquals("1.8.2-rc1", check.candidate?.versionName)
            assertEquals(true, check.latest?.prerelease)
        }
    }

    @Test fun handles403And429AsRateLimiting() {
        GitHubReleaseUpdates.requireSuccessfulResponse(200)
        for (code in listOf(403, 429)) {
            val failure = runCatching { GitHubReleaseUpdates.requireSuccessfulResponse(code) }.exceptionOrNull()
            assertEquals("GitHub 接口暂时限流，请稍后再试", failure?.message)
        }
        val other = runCatching { GitHubReleaseUpdates.requireSuccessfulResponse(404) }.exceptionOrNull()
        assertEquals("GitHub 接口返回 404", other?.message)
    }

    @Test fun emptyReleaseListIsExplicit() {
        val check = GitHubReleaseUpdates.parseReleases("[]", "1.5.0", 100500)
        assertNull(check.latest)
        assertNull(check.candidate)
        assertTrue(check.message.contains("还没有发布安装包"))
    }
}
