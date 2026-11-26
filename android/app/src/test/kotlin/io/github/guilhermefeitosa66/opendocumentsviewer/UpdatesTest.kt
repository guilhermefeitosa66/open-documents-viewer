package io.github.guilhermefeitosa66.opendocumentsviewer

import io.github.guilhermefeitosa66.opendocumentsviewer.update.DEFAULT_RELEASE_URL
import io.github.guilhermefeitosa66.opendocumentsviewer.update.Release
import io.github.guilhermefeitosa66.opendocumentsviewer.update.UpdateChecker
import io.github.guilhermefeitosa66.opendocumentsviewer.update.UpdateState
import io.github.guilhermefeitosa66.opendocumentsviewer.update.UpdateStore
import io.github.guilhermefeitosa66.opendocumentsviewer.update.UpdateVersion
import io.github.guilhermefeitosa66.opendocumentsviewer.update.parseReleases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun parsesTheFirstDottedNumber() {
        assertEquals(listOf(0, 34, 0), UpdateVersion.parse("android-v0.34.0-beta"))
        assertEquals(listOf(1, 2), UpdateVersion.parse("v1.2"))
        assertNull(UpdateVersion.parse("beta"))
        assertNull(UpdateVersion.parse(null))
    }

    @Test fun comparesNumerically() {
        assertTrue(UpdateVersion.isNewer("0.35.0", "0.34.0"))
        assertTrue(UpdateVersion.isNewer("0.10.0", "0.9.9"))
        assertTrue(UpdateVersion.isNewer("1.0", "0.99.99"))
        assertFalse(UpdateVersion.isNewer("0.34.0", "0.34.0"))
        assertFalse(UpdateVersion.isNewer("0.34", "0.34.0"))
        assertFalse(UpdateVersion.isNewer("0.33.9", "0.34.0"))
        assertFalse(UpdateVersion.isNewer("garbage", "0.34.0"))
    }

    private fun release(tag: String?, draft: Boolean = false, url: String = "https://github.com/guilhermefeitosa66/open-documents-viewer/releases/tag/$tag") =
        """{"tag_name": ${tag?.let { "\"$it\"" } ?: "null"}, "draft": $draft, "html_url": "$url"}"""

    @Test fun picksTheFirstAndroidReleaseNotTheLinuxOne() {
        val json = "[" + listOf(
            release("linux-v0.40.0-beta"),
            release("android-v0.36.0-beta", draft = true),
            release(null),
            release("android-v0.35.0-beta"),
            release("android-v0.34.0-beta"),
        ).joinToString(",") + "]"
        val found = parseReleases(json)!!
        assertEquals("0.35.0", found.version)
        assertTrue(found.url.endsWith("/android-v0.35.0-beta"))
    }

    @Test fun noAndroidReleaseOrBadReplyIsNothing() {
        assertNull(parseReleases("[" + release("linux-v0.40.0") + "]"))
        assertNull(parseReleases("[]"))
        assertNull(parseReleases("{\"message\": \"API rate limit exceeded\"}"))
        assertNull(parseReleases("not json"))
    }

    @Test fun onlyGithubPagesAreOpened() {
        val found = parseReleases("[" + release("android-v1.0.0", url = "https://evil.example/x") + "]")!!
        assertEquals(DEFAULT_RELEASE_URL, found.url)
    }

    @Test fun checkerNeverThrows() = runBlocking {
        val newer = "[" + release("android-v9.0.0") + "]"
        assertTrue(UpdateChecker("0.35.0") { newer }.check() is UpdateState.Available)
        assertEquals(UpdateState.UpToDate, UpdateChecker("9.0.0") { newer }.check())
        assertEquals(UpdateState.Unknown, UpdateChecker("0.35.0") { null }.check())
        assertEquals(UpdateState.Unknown, UpdateChecker("0.35.0") { throw IllegalStateException() }.check())
    }

    @Test fun onByDefault() {
        assertTrue(UpdateStore.DEFAULT_ENABLED)
    }

    @Test fun storeChecksOncePerProcessAndHonorsTheSwitch() = runBlocking {
        var calls = 0
        val checker = UpdateChecker("0.35.0") { calls++; "[" + release("android-v1.0.0") + "]" }
        var saved: Boolean? = null
        val store = UpdateStore(true, { saved = it }, checker, Dispatchers.Unconfined)
        store.checkOnce(this)?.join()
        assertEquals(UpdateState.Available(Release("1.0.0", "https://github.com/guilhermefeitosa66/open-documents-viewer/releases/tag/android-v1.0.0")), store.state.value)
        assertNull(store.checkOnce(this))
        assertEquals(1, calls)

        store.dismiss()
        assertTrue(store.dismissed.value)

        store.setEnabled(false)
        assertEquals(false, saved)
        assertEquals(UpdateState.Unknown, store.state.value)

        val off = UpdateStore(false, {}, checker, Dispatchers.Unconfined)
        assertNull(off.checkOnce(this))
        assertEquals(1, calls)
    }
}
