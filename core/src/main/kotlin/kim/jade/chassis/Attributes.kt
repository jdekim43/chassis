package kim.jade.chassis

import java.util.concurrent.ConcurrentHashMap

class AttributeKey<T : Any>(val name: String)

class Attributes {
    private val values = ConcurrentHashMap<AttributeKey<*>, Any>()

    operator fun <T : Any> get(key: AttributeKey<T>): T =
        getOrNull(key) ?: error("Attribute '${key.name}' is not set")

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrNull(key: AttributeKey<T>): T? = values[key] as T?

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrPut(key: AttributeKey<T>, create: () -> T): T =
        getOrNull(key) ?: synchronized(this) {
            getOrNull(key) ?: create().let { values.putIfAbsent(key, it) ?: it } as T
        }

    operator fun <T : Any> set(key: AttributeKey<T>, value: T) {
        values[key] = value
    }

    operator fun contains(key: AttributeKey<*>): Boolean = values.containsKey(key)
}
