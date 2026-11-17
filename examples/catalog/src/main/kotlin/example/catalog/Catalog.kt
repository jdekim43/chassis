package example.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ProductSource(private val file: File) {
    suspend fun read(): List<String> = withContext(Dispatchers.IO) {
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}

class CatalogStore(private val source: ProductSource) {
    @Volatile var names: List<String> = emptyList()
        private set

    @Volatile var failure: IOException? = null
        private set

    /** 원본을 다시 읽는다. 읽지 못하면 이전 목록을 그대로 두고 실패를 남긴다. */
    suspend fun refresh() {
        try {
            names = source.read()
            failure = null
        } catch (e: IOException) {
            failure = e
        }
    }
}
