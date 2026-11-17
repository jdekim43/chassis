package kim.jade.chassis.config

sealed class ConfigurationException(
    internal val description: String,
    val source: String,
    path: List<String> = emptyList(),
    cause: Throwable? = null,
    detail: String = "",
    val reason: String? = null,
) : RuntimeException(
    "$description (source=$source, section=${path.section()})" +
        (reason?.let { ": $it" } ?: "") + detail,
    cause,
) {
    val path: List<String> = path.toList()

    class ReadFailed(source: String, cause: Throwable) : ConfigurationException(
        "Configuration read failed", source, cause = cause,
    )

    class ParseFailed(source: String, cause: Throwable) : ConfigurationException(
        "Configuration parsing failed", source, cause = cause,
    )

    class DecodeFailed(
        source: String,
        path: List<String>,
        cause: Throwable,
        reason: String? = null,
    ) : ConfigurationException("Configuration decoding failed", source, path, cause, reason = reason)

    class MissingAnnotation(source: String) : ConfigurationException(
        "Configuration annotation is missing", source,
    )

    class UnsupportedFormat(source: String, val extension: String) : ConfigurationException(
        "No configuration reader is available for '$extension'", source,
    )

    class ValidationFailed(
        source: String,
        path: List<String>,
        cause: Throwable? = null,
        issues: List<ConfigurationIssue> = emptyList(),
    ) : ConfigurationException(
        "Configuration validation failed", source, path, cause,
        if (issues.isEmpty()) "" else "; issues: " + issues.summary(),
    ) {
        val issues: List<ConfigurationIssue> = issues.toList()
    }
}

internal fun List<String>.section(): String = joinToString(".").ifEmpty { "<root>" }

internal fun List<ConfigurationIssue>.summary(): String = joinToString(", ") { "${it.key}=${it.code}" }
