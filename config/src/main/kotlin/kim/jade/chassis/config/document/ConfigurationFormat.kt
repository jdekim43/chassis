package kim.jade.chassis.config.document

import kim.jade.chassis.config.ConfigurationException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

abstract class ConfigurationFormat {
    protected abstract fun parseTree(text: String): JsonObject

    fun parse(text: String, source: String = "string"): ConfigurationDocument = try {
        ConfigurationDocument(parseTree(text), source)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ConfigurationException) {
        throw failure
    } catch (failure: Exception) {
        throw ConfigurationException.ParseFailed(source, failure)
    }

    fun read(stream: InputStream, source: String = "stream"): ConfigurationDocument =
        read(InputStreamReader(stream, Charsets.UTF_8.newDecoder()), source)

    fun read(reader: Reader, source: String = "reader"): ConfigurationDocument {
        val text = try {
            reader.readText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ConfigurationException) {
            throw failure
        } catch (failure: Exception) {
            throw ConfigurationException.ReadFailed(source, failure)
        }
        return parse(text, source)
    }

    fun read(file: File, source: String = "file"): ConfigurationDocument = try {
        file.inputStream().use { read(it, source) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ConfigurationException) {
        throw failure
    } catch (failure: Exception) {
        throw ConfigurationException.ReadFailed(source, failure)
    }
}
