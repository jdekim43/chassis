package kim.jade.chassis.cli

import com.github.ajalt.clikt.command.CoreSuspendingCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandTreeTest {
    private class Leaf(name: String) : CoreSuspendingCliktCommand(name) {
        val text by argument()
        override suspend fun run() = Unit
    }
    private class Group(name: String) : CoreSuspendingCliktCommand(name) {
        override suspend fun run() = Unit
    }
    private class Parent : CoreSuspendingCliktCommand("parent") {
        val required by option("--required").required()
        override suspend fun run() = Unit
    }
    private class Chained : CoreSuspendingCliktCommand("chained") {
        override val allowMultipleSubcommands = true
        override suspend fun run() = Unit
    }

    @Test fun `parsing returns the chain from the root to the selected command`() {
        val leaf = Leaf("echo")
        val group = Group("group").subcommands(leaf)
        val root = RootCommand("tool", listOf(group))
        val chain = parseInvocations(root, listOf("group", "echo", "hi"))
        assertEquals(listOf(root, group, leaf), chain.map { it.command })
        assertEquals("hi", leaf.text)
    }

    @Test fun `no command yields only the root`() {
        val root = RootCommand("tool", listOf(Leaf("echo")))
        assertEquals(listOf<CoreSuspendingCliktCommand>(root), parseInvocations(root, emptyList()).map { it.command })
    }

    @Test fun `help is reported without running anything`() {
        val root = RootCommand("tool", listOf(Leaf("echo")))
        assertFailsWith<PrintHelpMessage> { parseInvocations(root, listOf("--help")) }
        assertFailsWith<PrintHelpMessage> { parseInvocations(root, listOf("echo", "--help")) }
    }

    @Test fun `help for a child works when its parent has a required option`() {
        val root = RootCommand("tool", listOf(Parent().subcommands(Leaf("echo"))))
        assertFailsWith<PrintHelpMessage> { parseInvocations(root, listOf("parent", "echo", "--help")) }
        assertFailsWith<UsageError> { parseInvocations(root, listOf("parent", "echo", "hi")) }
    }

    @Test fun `a missing argument and an unknown command are usage errors`() {
        val root = RootCommand("tool", listOf(Leaf("echo")))
        assertFailsWith<UsageError> { parseInvocations(root, listOf("echo")) }
        assertFailsWith<UsageError> { parseInvocations(root, listOf("nope")) }
    }

    @Test fun `commands with the same name under one parent are rejected`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            RootCommand("tool", listOf(Leaf("echo"), Leaf("echo")))
        }
        assertContains(thrown.message!!, "Duplicate command 'echo'")
    }

    @Test fun `duplicates inside a group are rejected with their path`() {
        val group = Group("group").subcommands(Leaf("echo"), Leaf("echo"))
        val thrown = assertFailsWith<IllegalArgumentException> { RootCommand("tool", listOf(group)) }
        assertContains(thrown.message!!, "Duplicate command 'group echo'")
    }

    @Test fun `command chaining is rejected`() {
        val thrown = assertFailsWith<IllegalArgumentException> { RootCommand("tool", listOf(Chained())) }
        assertContains(thrown.message!!, "chained")
    }
}
