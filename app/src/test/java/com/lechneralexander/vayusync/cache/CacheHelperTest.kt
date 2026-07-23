package com.lechneralexander.vayusync.cache

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Behavioural tests for the custom disk cache. Uses Robolectric so android.net.Uri,
 * Bitmap and Context behave like the real framework. The companion object holds static
 * mutable state (cachedKeys / indexLoaded); it is reset before each test via reflection
 * so tests do not leak into one another.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CacheHelperTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetStaticState() {
        CacheHelper::class.java.getDeclaredField("cachedKeys").apply { isAccessible = true }
            .let { @Suppress("UNCHECKED_CAST") (it.get(null) as MutableSet<String>).clear() }
        CacheHelper::class.java.getDeclaredField("indexLoaded").apply { isAccessible = true }
            .set(null, false)
        File(context.cacheDir, "image_cache").deleteRecursively()
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // ---- key generation -------------------------------------------------------

    @Test
    fun diskCacheKey_replacesSlashesInLastSegment() {
        val uri = Uri.parse("content://auth/document/AAA%2FDCIM%2Fimg.jpg")
        assertThat(CacheHelper.getDiskCacheKey(uri)).isEqualTo("AAA_DCIM_img.jpg")
    }

    @Test
    fun diskCacheKey_nullWhenNoLastSegment() {
        assertThat(CacheHelper.getDiskCacheKey(Uri.parse("content://auth"))).isNull()
    }

    @Test
    fun tierKeys_arePrefixedWithFullUri() {
        val uri = Uri.parse("content://auth/document/img.jpg")
        assertThat(CacheHelper.getThumbnailCacheKey(uri)).isEqualTo("thumb_$uri")
        assertThat(CacheHelper.getPreviewCacheKey(uri)).isEqualTo("prev_$uri")
        assertThat(CacheHelper.getFullViewCacheKey(uri)).isEqualTo("full_$uri")
    }

    /**
     * Documents a KNOWN collision in the current keying scheme (Phase 4 will fix it):
     * because getDiskCacheKey only uses the decoded last path segment and replaces '/'
     * with '_', two distinct URIs can map to the same cache file. When the keying is
     * hardened this test is expected to change.
     */
    @Test
    fun diskCacheKey_currentSchemeCollides_documentedRisk() {
        val a = Uri.parse("content://auth/document/x%2Fy") // last segment "x/y" -> "x_y"
        val b = Uri.parse("content://auth/document/x_y")   // last segment "x_y" -> "x_y"
        assertThat(CacheHelper.getDiskCacheKey(a)).isEqualTo(CacheHelper.getDiskCacheKey(b))
    }

    // ---- save / index ---------------------------------------------------------

    private fun uri(name: String) = Uri.parse("content://auth/document/$name")

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test
    fun save_writesFile_andMarksCached() = runTest {
        val u = uri("photo1.jpg")
        assertThat(CacheHelper.isCached(u)).isFalse()

        val file = CacheHelper.saveBitmapToCache(context, u, bitmap())

        assertThat(file).isNotNull()
        assertThat(file!!.exists()).isTrue()
        assertThat(file.name).isEqualTo("photo1.jpg")
        assertThat(CacheHelper.isCached(u)).isTrue()
        // no leftover temp file
        assertThat(File(file.parentFile, "photo1.jpg.tmp").exists()).isFalse()
    }

    @Test
    fun save_whenFileAlreadyExists_returnsExistingAndMarksCached() = runTest {
        val u = uri("photo2.jpg")
        val first = CacheHelper.saveBitmapToCache(context, u, bitmap())!!
        // wipe the in-memory index but keep the file on disk
        CacheHelper::class.java.getDeclaredField("cachedKeys").apply { isAccessible = true }
            .let { @Suppress("UNCHECKED_CAST") (it.get(null) as MutableSet<String>).clear() }
        assertThat(CacheHelper.isCached(u)).isFalse()

        val second = CacheHelper.saveBitmapToCache(context, u, bitmap())

        assertThat(second).isEqualTo(first)
        assertThat(CacheHelper.isCached(u)).isTrue()
    }

    @Test
    fun invalidate_removesFromIndexOnly() = runTest {
        val u = uri("photo3.jpg")
        val file = CacheHelper.saveBitmapToCache(context, u, bitmap())!!

        CacheHelper.invalidateCached(u)

        assertThat(CacheHelper.isCached(u)).isFalse()
        assertThat(file.exists()).isTrue() // file itself is untouched
    }

    @Test
    fun loadCacheIndex_populatesFromExistingDirectory() = runTest {
        val dir = File(context.cacheDir, "image_cache").apply { mkdirs() }
        File(dir, "photo4.jpg").writeText("x")
        val u = uri("photo4.jpg")
        assertThat(CacheHelper.isCached(u)).isFalse()

        CacheHelper.loadCacheIndex(dir)

        assertThat(CacheHelper.isCached(u)).isTrue()
    }

    // ---- failure / cancellation cleanup --------------------------------------

    @Test
    fun save_whenCompressThrows_deletesTempAndRethrows() = runTest {
        val u = uri("photo5.jpg")
        val bmp = mockk<Bitmap>()
        every { bmp.compress(any(), any(), any()) } throws IOException("boom")

        assertThrows(IOException::class.java) {
            runBlockingSave(u, bmp)
        }

        val dir = File(context.cacheDir, "image_cache")
        assertThat(File(dir, "photo5.jpg").exists()).isFalse()
        assertThat(File(dir, "photo5.jpg.tmp").exists()).isFalse()
        assertThat(CacheHelper.isCached(u)).isFalse()
    }

    @Test
    fun save_whenCancelled_deletesTempAndPropagates() = runTest {
        val u = uri("photo6.jpg")
        val bmp = mockk<Bitmap>()
        every { bmp.compress(any(), any(), any()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlockingSave(u, bmp)
        }

        val dir = File(context.cacheDir, "image_cache")
        assertThat(File(dir, "photo6.jpg.tmp").exists()).isFalse()
        assertThat(CacheHelper.isCached(u)).isFalse()
    }

    private fun runBlockingSave(u: Uri, bmp: Bitmap) = kotlinx.coroutines.runBlocking {
        CacheHelper.saveBitmapToCache(context, u, bmp)
    }
}
