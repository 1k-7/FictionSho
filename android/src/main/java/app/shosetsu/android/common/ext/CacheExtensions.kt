package app.shosetsu.android.common.ext

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/*
 * This file is part of Shosetsu.
 *
 * Shosetsu is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Shosetsu is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Shosetsu.  If not, see <https://www.gnu.org/licenses/>.
 */


/**
 * shosetsu
 * 10 / May / 2020
 */

operator fun <K : Any, V : Any> Cache<K, V>.set(key: K, value: V): Unit = put(key, value)
operator fun <K : Any, V : Any> Cache<K, V>.get(key: K): V? = getIfPresent(key)
fun <K : Any, V : Any> CacheBuilder<K, V>.expireAfterAccess(duration: Duration) = expireAfterAccess(duration.toJavaDuration())
fun <K : Any, V : Any> CacheBuilder<K, V>.expireAfterWrite(duration: Duration) = expireAfterWrite(duration.toJavaDuration())
