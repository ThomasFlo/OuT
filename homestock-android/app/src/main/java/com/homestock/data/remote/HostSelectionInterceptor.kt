package com.homestock.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

/**
 * Rewrites every request to point at the NAS host/port currently configured in
 * settings, so we never have to rebuild the Retrofit instance when the user
 * changes the NAS address.
 */
class HostSelectionInterceptor : Interceptor {

    private val host = AtomicReference("192.168.1.3")
    private val port = AtomicReference(8080)

    fun update(newHost: String, newPort: Int) {
        host.set(newHost.trim())
        port.set(newPort)
    }

    fun baseHttpUrl(): String = "http://${host.get()}:${port.get()}"

    fun baseWsUrl(): String = "ws://${host.get()}:${port.get()}/ws"

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .scheme("http")
            .host(host.get())
            .port(port.get())
            .build()
        // Validate (defensive) — fall back to original if host is malformed.
        baseHttpUrl().toHttpUrlOrNull() ?: return chain.proceed(original)
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
