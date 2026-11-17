package kim.jade.chassis.config

import kim.jade.chassis.Application
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MetaSerializable
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier

@OptIn(ExperimentalSerializationApi::class)
@MetaSerializable
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Configuration(vararg val path: String)

interface ValidatableConfiguration {
    fun validate(): List<ConfigurationIssue>
}

inline fun <reified T : Any> Module.configuration(
    qualifier: Qualifier? = null,
    noinline load: (() -> T)? = null,
) {
    single<T>(qualifier = qualifier, createdAtStart = true) {
        get<Application>().configurations.resolve<T>(load)
    }
}
