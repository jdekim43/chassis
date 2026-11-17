package kim.jade.chassis.config.document

import java.io.File
import java.util.ServiceLoader

interface ConfigurationReader {

    val extensions: Set<String>

    fun read(file: File): ConfigurationDocument

    companion object {
        fun loadAll(classLoader: ClassLoader? = null): Map<String, ConfigurationReader> = buildMap {
            val effectiveClassLoader = classLoader
                ?: Thread.currentThread().contextClassLoader
                ?: ConfigurationReader::class.java.classLoader

            for (reader in ServiceLoader.load(ConfigurationReader::class.java, effectiveClassLoader)) {
                require(reader.extensions.isNotEmpty()) {
                    "${reader.javaClass.name} must declare configuration extensions"
                }
                for (extension in reader.extensions) {
                    require(extension.isNotBlank() && extension.none(Char::isWhitespace) && !extension.startsWith('.')) {
                        "${reader.javaClass.name}: configuration extensions must be nonblank and omit whitespace and the leading dot"
                    }
                    val key = extension.lowercase()
                    val previous = get(key)
                    check(previous == null || previous === reader) {
                        "Multiple configuration readers for '$key': ${previous?.javaClass?.name}, ${reader.javaClass.name}"
                    }
                    put(key, reader)
                }
            }
        }
    }
}
