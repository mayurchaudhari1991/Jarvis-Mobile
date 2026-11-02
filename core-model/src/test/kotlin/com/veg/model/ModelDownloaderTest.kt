package com.veg.model

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

class ModelDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val body = ByteArray(4096) { (it % 251).toByte() }
    private val bodySha = MessageDigest.getInstance("SHA-256")
        .digest(body)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun spec() = ModelSpec(
        id = "test-model",
        url = server.url("/model.gguf").toString(),
        sha256 = bodySha,
        sizeBytes = body.size.toLong()
    )

    @Test
    fun `successful download emits progress then done`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val downloader = ModelDownloader(client, temp.root)
        val emissions = mutableListOf<DownloadProgress>()

        downloader.download(spec()).test(timeout = 30.seconds) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                emissions += item
                item = awaitItem()
            }
            emissions += item
            awaitComplete()
        }

        val last = emissions.last()
        assertTrue("expected Done, got $last", last is DownloadProgress.Done)
        assertTrue(emissions.any { it is DownloadProgress.Running })
        assertTrue(emissions.any { it is DownloadProgress.Verifying })
        assertEquals(body.size.toLong(), (last as DownloadProgress.Done).file.length())
    }

    @Test
    fun `checksum mismatch fails and deletes the partial file`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val badSpec = spec().copy(sha256 = "0".repeat(64))
        val downloader = ModelDownloader(client, temp.root)

        var last: DownloadProgress? = null
        downloader.download(badSpec).test(timeout = 30.seconds) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            last = item
            awaitComplete()
        }

        assertTrue("expected Failed, got $last", last is DownloadProgress.Failed)
        assertTrue(temp.root.listFiles().orEmpty().none { it.name.endsWith(".gguf") })
    }

    @Test
    fun `resume sends a range header when a partial file exists`() = runTest {
        val half = body.size / 2
        val partial = java.io.File(temp.root, "test-model.gguf.part")
        partial.writeBytes(body.copyOfRange(0, half))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $half-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body.copyOfRange(half, body.size)))
        )

        val downloader = ModelDownloader(client, temp.root)
        downloader.download(spec()).test(timeout = 30.seconds) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            assertTrue("expected Done, got $item", item is DownloadProgress.Done)
            awaitComplete()
        }

        val request = server.takeRequest()
        assertEquals("bytes=$half-", request.getHeader("Range"))
    }

    @Test
    fun `server error is reported as retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val downloader = ModelDownloader(client, temp.root)
        var last: DownloadProgress? = null
        downloader.download(spec()).test(timeout = 30.seconds) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            last = item
            awaitComplete()
        }

        val failed = last as DownloadProgress.Failed
        assertTrue(failed.canRetry)
    }

    @Test
    fun `already complete file skips the network entirely`() = runTest {
        val done = java.io.File(temp.root, "test-model.gguf")
        done.writeBytes(body)

        val downloader = ModelDownloader(client, temp.root)
        downloader.download(spec()).test(timeout = 30.seconds) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            assertTrue(item is DownloadProgress.Done)
            awaitComplete()
        }

        assertEquals(0, server.requestCount)
    }
}
