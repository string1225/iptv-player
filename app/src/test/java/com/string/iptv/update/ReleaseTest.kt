package com.string.iptv.update

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class ReleaseTest {
    private val content = "signed-apk-fixture".toByteArray()
    private val digest = "sha256:" + MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private fun asset() = ReleaseAsset(ReleasePolicy.APK_NAME,
        "https://github.com/string1225/iptv-player/releases/download/v0.2.1/iptv-player.apk", content.size.toLong(), digest)

    @Test fun versionCodesAreMonotonicAndOnlyStableTagsAreAccepted() {
        assertTrue(AppVersion.parse("v0.2.10")!! > AppVersion.parse("0.2.9")!!)
        assertEquals(1_002_003, AppVersion.parse("v1.2.3")?.code)
        assertNull(AppVersion.parse("v1.0.0-beta"))
        assertNull(AppVersion.parse("999999.0.0"))
        assertNull(AppVersion.parse("0.1000.0"))
    }

    @Test fun releaseSelectionRequiresOfficialApkAndDigest() {
        assertEquals(2001, ReleasePolicy.select("v0.2.1", false, false, listOf(asset()), "notes").version.code)
        assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.select("v0.2.1", false, true, listOf(asset()), "") }
        assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.select("v0.2.1", false, false, listOf(asset().copy(digest = "")), "") }
        assertThrows(IllegalArgumentException::class.java) { ReleasePolicy.select("v0.2.1", false, false, listOf(asset().copy(url = "https://example.com/file.apk")), "") }
        assertEquals(listOf("https://gh-proxy.org/${asset().url}", asset().url), ReleasePolicy.downloadUrls(asset().url))
    }

    @Test fun failedProxyFallsBackAndValidatesBeforePublishingFile() = downloadTest(503, false)
    @Test fun corruptProxyFallsBackToOrigin() = downloadTest(200, true)

    private fun downloadTest(proxyStatus: Int, corruptProxy: Boolean) {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += exchange.requestURI.path
            val bytes = if (exchange.requestURI.path == "/proxy" && corruptProxy) ByteArray(content.size) else content
            val status = if (exchange.requestURI.path == "/proxy") proxyStatus else 200
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val directory = Files.createTempDirectory("iptv-update-test").toFile()
        val target = File(directory, "update.apk")
        val downloader = ReleaseDownloader(urls = { listOf("http://127.0.0.1:${server.address.port}/proxy", "http://127.0.0.1:${server.address.port}/origin") })
        try {
            var validations = 0
            downloader.download(asset(), target, {
                assertFalse(target.exists())
                assertArrayEquals(content, it.readBytes())
                validations++
            }) { _, _ -> }
            assertEquals(listOf("/proxy", "/origin"), requests)
            assertEquals(1, validations)
            assertArrayEquals(content, target.readBytes())
            assertFalse(File(directory, "update.part.apk").exists())
        } finally { downloader.close(); server.stop(0); directory.deleteRecursively() }
    }

    @Test fun rejectedApkNeverBecomesInstallable() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.start()
        val directory = Files.createTempDirectory("iptv-update-test").toFile()
        val target = File(directory, "update.apk")
        val downloader = ReleaseDownloader(urls = { listOf("http://127.0.0.1:${server.address.port}/apk") })
        try {
            assertThrows(java.io.IOException::class.java) { downloader.download(asset(), target, { error("签名不符") }) { _, _ -> } }
            assertFalse(target.exists())
            assertFalse(File(directory, "update.part.apk").exists())
        } finally { downloader.close(); server.stop(0); directory.deleteRecursively() }
    }
}
