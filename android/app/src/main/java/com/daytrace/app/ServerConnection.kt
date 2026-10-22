package com.daytrace.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ServerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val addressState = MutableStateFlow(prefs.getString("baseUrl", "").orEmpty())
    val address = addressState.asStateFlow()

    suspend fun save(input: String) {
        val normalized = ServerAddress.normalize(input)
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("baseUrl", normalized).commit()) { "地址保存失败，请重试。" }
        }
        addressState.value = normalized
    }
}

data class ConnectionResult(val success: Boolean, val message: String)

object ServerConnection {
    /** Only probes health. No audio, credentials, cookies or Authorization header are sent. */
    suspend fun test(address: String): ConnectionResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val started = System.nanoTime()
        try {
            connection = URL(ServerAddress.healthUrl(address)).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val hint = when (code) {
                    401, 403 -> "；服务要求认证，当前客户端不发送认证信息"
                    404 -> "；未找到 /health，请检查服务地址及路径"
                    in 300..399 -> "；请填写重定向后的服务地址"
                    else -> ""
                }
                ConnectionResult(false, "测试失败：服务返回 HTTP $code$hint。")
            } else {
                // Bound the response: a proxy/login page must not count as a healthy service.
                val body = connection.inputStream.use { stream ->
                    val bytes = ByteArray(4097)
                    var count = 0
                    while (count < bytes.size) {
                        val read = stream.read(bytes, count, bytes.size - count)
                        if (read == -1) break
                        count += read
                    }
                    if (count > 4096) null else String(bytes, 0, count, Charsets.UTF_8)
                }
                val healthy = body != null && runCatching { JSONObject(body).optString("status") == "ok" }.getOrDefault(false)
                if (healthy) ConnectionResult(true, "连接成功：服务正常（${(System.nanoTime() - started) / 1_000_000} ms）。")
                else ConnectionResult(false, "已连接，但健康检查响应异常：需要返回 status 为 ok 的 JSON。")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            ConnectionResult(false, "连接超时，请检查服务地址、网络及防火墙后重试。")
        } catch (_: UnknownHostException) {
            ConnectionResult(false, "无法解析主机名，请检查服务地址和网络。")
        } catch (_: ConnectException) {
            ConnectionResult(false, "无法连接服务，请检查 IP、端口、服务是否启动及设备网络。")
        } catch (_: SSLException) {
            ConnectionResult(false, "HTTPS 证书或握手失败，请检查服务的证书配置。")
        } catch (e: Exception) {
            ConnectionResult(false, "测试失败：${e.message ?: "网络异常，请稍后重试"}")
        } finally {
            connection?.disconnect()
        }
    }
}
