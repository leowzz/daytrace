package com.daytrace.app

import java.net.URI
import java.util.Locale

object ServerAddress {
    fun normalize(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "请输入服务地址，例如 http://192.168.177.100:8000" }
        val uri = try { URI(value) } catch (_: Exception) {
            throw IllegalArgumentException("地址格式不正确，请填写完整的 HTTP 或 HTTPS 地址。")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "地址必须以 http:// 或 https:// 开头。" }
        require(!uri.host.isNullOrBlank()) { "请填写有效的主机名或 IP 地址。" }
        require(uri.port == -1 || uri.port in 1..65535) { "端口必须在 1–65535 之间。" }
        require(!uri.rawAuthority.endsWith(":")) { "请填写有效端口。" }
        require(uri.rawUserInfo == null) { "当前无需认证，请勿在地址中填写用户名或密码。" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "服务地址不能包含查询参数或 # 片段。" }
        return "$scheme://${uri.rawAuthority}${uri.rawPath.orEmpty().trimEnd('/')}"
    }

    fun healthUrl(input: String): String = "${normalize(input)}/health"
}
