package app.shosetsu.android.datasource.local.memory.impl

import app.shosetsu.android.common.ext.expireAfterWrite
import app.shosetsu.android.common.ext.get
import app.shosetsu.android.common.ext.set
import app.shosetsu.android.datasource.local.memory.base.IMemDataSourceFactory
import com.google.common.cache.CacheBuilder
import kotlin.time.Duration

class GuavaMemDataSourceFactory : IMemDataSourceFactory {
    override fun <K : Any, V : Any> create(
        expireDuration: Duration,
        maxSize: Int
    ): IMemDataSourceFactory.Source<K, V> = Source(expireDuration, maxSize.toLong())

    private class Source<K : Any, V : Any>(
        expireTime: Duration,
        maxSize: Long,
    ) : IMemDataSourceFactory.Source<K, V> {
        private val cache = CacheBuilder.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(expireTime)
            .build<K, V>()

        override fun remove(key: K): Boolean {
            cache.invalidate(key)
            return true
        }

        override fun set(key: K, value: V) {
            cache[key] = value
        }

        override fun contains(key: K): Boolean = cache.getIfPresent(key) != null
        override fun get(key: K): V? = cache[key]

        override fun clear() {
            cache.invalidateAll()
            cache.cleanUp()
        }
    }
}
