package example.catalog

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogStoreTest {
    @TempDir lateinit var directory: File

    @Test fun `a failed refresh keeps the previous names until the source can be read again`() = runBlocking<Unit> {
        val products = File(directory, "products.txt").apply { writeText("Coffee\nTea") }
        val store = CatalogStore(ProductSource(products))
        store.refresh()
        assertEquals(listOf("Coffee", "Tea"), store.names)
        assertNull(store.failure)

        products.delete()
        store.refresh()
        assertEquals(listOf("Coffee", "Tea"), store.names)
        assertNotNull(store.failure)

        products.writeText("Milk")
        store.refresh()
        assertEquals(listOf("Milk"), store.names)
        assertNull(store.failure)
    }
}
