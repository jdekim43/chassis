package kim.jade.chassis.config

import kim.jade.chassis.config.document.ConfigurationDocument
import kim.jade.chassis.config.internal.MergedDocuments
import kim.jade.kotlinx.logger.Logger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.serializer

class ConfigurationResolver {
    private val logger = Logger.named("kim.jade.chassis.config")
    private val merged = AtomicReference(MergedDocuments(emptyList()))

    fun add(document: ConfigurationDocument) {
        merged.updateAndGet { MergedDocuments(listOf(document) + it.documents) }
        logger.info("Configuration added: ${document.source}")
    }

    fun resolve(type: KType, load: (() -> Any)? = null): Any = try {
        resolveOrThrow(type, load)
    } catch (failure: ConfigurationException) {
        logger.error(
            failure.description + (failure.reason?.let { ": $it" } ?: ""),
            meta = buildMap {
                put("source", failure.source)
                put("section", failure.path.section())
                if (failure is ConfigurationException.ValidationFailed && failure.issues.isNotEmpty()) {
                    put("issues", failure.issues.summary())
                }
            },
        )
        throw failure
    }

    private fun resolveOrThrow(type: KType, load: (() -> Any)?): Any {
        val annotation = (type.classifier as? KClass<*>)?.java
            ?.getAnnotation(Configuration::class.java)
            ?: throw ConfigurationException.MissingAnnotation(type.toString())
        val path = annotation.path.toList()
        val value = if (load != null) load() else read(type, path)

        if (value !is ValidatableConfiguration) {
            return value
        }

        val issues = try {
            value.validate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw ConfigurationException.ValidationFailed(type.toString(), path, cause = e)
        }

        if (issues.isNotEmpty()) {
            val prefix = path.joinToString(".")
            throw ConfigurationException.ValidationFailed(
                type.toString(), path,
                issues = issues.map { issue ->
                    issue.copy(key = when {
                        path.isEmpty() -> issue.key
                        issue.key.isEmpty() -> prefix
                        else -> "$prefix.${issue.key}"
                    })
                },
            )
        }
        return value
    }

    private fun read(type: KType, path: List<String>): Any {
        val documents = merged.get()
        val deserializer = try {
            serializer(type)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ConfigurationException.DecodeFailed(documents.sources, path, failure)
        }
        // 설정 클래스의 섹션은 null로 디코드되지 않는다.
        return documents.read(deserializer, path)!!
    }
}

inline fun <reified T : Any> ConfigurationResolver.resolve(noinline load: (() -> T)? = null): T =
    resolve(typeOf<T>(), load) as T
