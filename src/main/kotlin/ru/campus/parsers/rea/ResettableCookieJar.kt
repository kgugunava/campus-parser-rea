/*
 * Copyright 2022 LLC Campus.
 */

package ru.campus.parsers.rea

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicReference

/**
 * То же самое, что `InMemoryCookieJar` из `parser-sdk`, но с [reset] — нужен, чтобы
 * [ru.campus.parsers.rea.group.ReaGroupScheduleCollector] мог начинать сессию заново перед каждой
 * группой (см. его KDoc, раздел про независимую сессию на группу): SDK-шный `InMemoryCookieJar` не
 * даёт способа очистить куки снаружи.
 */
class ResettableCookieJar : CookieJar {
    private val container: AtomicReference<List<Cookie>> = AtomicReference(emptyList())

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return container.get().filter { it.matches(url) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val validCookies: List<Cookie> = cookies.filter { it.name.isNotBlank() }

        do {
            val currentList: List<Cookie> = container.get()
            val newList: List<Cookie> = currentList.filter { cookie ->
                cookie.matches(url) && cookie.name !in validCookies.map { it.name }
            } + validCookies
        } while (!container.compareAndSet(currentList, newList))
    }

    fun reset() {
        container.set(emptyList())
    }
}
