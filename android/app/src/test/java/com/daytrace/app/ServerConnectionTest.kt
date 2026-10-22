package com.daytrace.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ServerConnectionTest {
    @Test fun normalizesLanAddressesAndPreservesProxyPrefix() {
        assertEquals("http://192.168.177.100:8000", ServerAddress.normalize("  http://192.168.177.100:8000///  "))
        assertEquals("https://example.com/daytrace/health", ServerAddress.healthUrl("https://example.com/daytrace/"))
        assertEquals("http://[::1]:8000/health", ServerAddress.healthUrl("http://[::1]:8000"))
        assertEquals("http://localhost/health", ServerAddress.healthUrl("HTTP://localhost"))
    }

    @Test fun rejectsMalformedAddressesAndEmbeddedAuthentication() {
        listOf("", "192.168.177.100:8000", "ftp://example.com", "http://", "http://bad host",
            "http://localhost:", "http://localhost:0", "http://localhost:65536", "http://localhost:-1",
            "http://user:password@localhost", "http://localhost?token=secret", "http://localhost/#fragment"
        ).forEach { address ->
            assertTrue("Should reject $address", runCatching { ServerAddress.normalize(address) }.isFailure)
        }
    }

    private fun probe(code: Int, body: String, check: (ConnectionResult, List<String>) -> Unit) {
        val request = AtomicReference<List<String>>()
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val response = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine().split(" ")
                val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val authorization = headers.firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim().orEmpty()
                request.set(listOf(line[0], line[1], authorization))
                val bytes = body.toByteArray()
                val redirect = if (code == 302) "Location: /redirected\r\n" else ""
                socket.getOutputStream().use { output ->
                    output.write(("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n${redirect}\r\n").toByteArray())
                    output.write(bytes)
                }
            }
        }
        try {
            val result = runBlocking { ServerConnection.test("http://127.0.0.1:${server.localPort}/daytrace/") }
            response.get(6, TimeUnit.SECONDS)
            check(result, request.get())
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun checksHealthWithoutAuthentication() = probe(200, "{\"status\":\"ok\"}") { result, request ->
        assertTrue(result.message, result.success)
        assertEquals(listOf("GET", "/daytrace/health", ""), request)
    }

    @Test fun rejectsHtmlAndUnhealthyOrOversizedResponses() {
        listOf("<html>Login</html>", "{\"status\":\"down\"}", "{}", " ".repeat(4097) + "{\"status\":\"ok\"}").forEach { body ->
            probe(200, body) { result, _ -> assertFalse(result.success) }
        }
    }

    @Test fun reportsHttpErrorsAndDoesNotFollowRedirects() {
        listOf(401, 403, 404, 500, 302).forEach { code ->
            probe(code, "{}") { result, request ->
                assertFalse(result.success)
                assertTrue(result.message, result.message.contains("HTTP $code"))
                assertEquals("/daytrace/health", request[1])
            }
        }
    }

    @Test fun connectionRefusedReturnsAnActionableFailure() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val result = runBlocking { ServerConnection.test("http://127.0.0.1:$port") }
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("无法连接服务"))
    }
}
