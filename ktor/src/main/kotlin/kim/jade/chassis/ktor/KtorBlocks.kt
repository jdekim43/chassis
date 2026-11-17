package kim.jade.chassis.ktor

import kim.jade.chassis.Application
import kim.jade.chassis.AttributeKey
import org.koin.core.scope.Scope
import java.util.concurrent.CopyOnWriteArrayList
import io.ktor.server.application.Application as KtorApplication

private val KtorBlocksKey = AttributeKey<KtorBlocks>("chassis.ktor.blocks")

internal val Application.ktorBlocks: KtorBlocks
    get() = attributes.getOrPut(KtorBlocksKey) { KtorBlocks() }

internal class KtorBlocks {
    private val blocks = CopyOnWriteArrayList<KtorBlock>()

    val all: List<KtorBlock> get() = blocks.toList()

    fun add(block: KtorBlock) {
        blocks += block
    }
}

internal class KtorBlock(private val scope: Scope, private val block: Scope.(KtorApplication) -> Unit) {
    fun apply(server: KtorApplication) = scope.block(server)
}
