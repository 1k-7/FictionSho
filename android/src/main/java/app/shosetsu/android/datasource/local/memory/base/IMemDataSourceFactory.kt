package app.shosetsu.android.datasource.local.memory.base

import kotlin.time.Duration

interface IMemDataSourceFactory {
	fun <K : Any, V : Any> create(expireDuration: Duration, maxSize: Int): Source<K, V>

	interface Source<K : Any, V : Any> {
		fun remove(key: K): Boolean
		operator fun set(key: K, value: V)
		fun contains(key: K): Boolean
		operator fun get(key: K): V?
		fun clear()
	}
}
