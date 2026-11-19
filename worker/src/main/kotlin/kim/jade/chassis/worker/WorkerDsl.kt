package kim.jade.chassis.worker

import org.koin.core.definition.Definition
import org.koin.core.definition.KoinDefinition
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.bind

inline fun <reified T : Worker> Module.worker(
    qualifier: Qualifier? = null,
    noinline definition: Definition<T>,
): KoinDefinition<T> {
    includes(workerModule)
    val registered = single<T>(qualifier, definition = definition)
    if (qualifier != null) {
        registered bind Worker::class
    } else if (T::class != Worker::class) {
        factory<Worker>(named("chassis.worker:${T::class.java.name}")) { parameters ->
            get<T> { parameters }
        }
    }
    return registered
}
