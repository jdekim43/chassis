package kim.jade.chassis.ktor

import kim.jade.chassis.ApplicationStatus
import kim.jade.chassis.health.Health
import kim.jade.chassis.health.liveness
import kim.jade.chassis.health.readiness
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthEndpointsTest {
    private fun Response.lines() = body.lines().filter { it.isNotEmpty() }

    private fun healthTest(checks: Module.() -> Unit, block: suspend (port: Int) -> Unit) =
        serverTest(module {
            testServer()
            healthEndpoints()
            checks()
        }) { app ->
            app.start()
            app.koin.get<KtorServer>().start()
            block(app.serverPort())
        }

    @Test fun `both endpoints answer 200 when every check is healthy`() = healthTest({
        liveness("loop") { Health.healthy("ticking") }
        readiness("storage") { Health.healthy() }
    }) { port ->
        val live = fetch(port, "/health/live")
        assertEquals(200, live.status)
        assertTrue(live.contentType.startsWith("text/plain"), live.contentType)
        assertEquals(listOf("ACTIVE healthy", "loop: healthy - ticking"), live.lines())
        val ready = fetch(port, "/health/ready")
        assertEquals(200, ready.status)
        assertEquals(listOf("ACTIVE healthy", "storage: healthy"), ready.lines())
    }

    @Test fun `an unhealthy readiness check answers 503 on ready only`() = healthTest({
        readiness("catalog") { Health.unhealthy("cannot read products.txt", IllegalStateException("secret detail")) }
        readiness("storage") { Health.healthy() }
    }) { port ->
        val ready = fetch(port, "/health/ready")
        assertEquals(503, ready.status)
        assertTrue(ready.contentType.startsWith("text/plain"), ready.contentType)
        assertEquals("ACTIVE unhealthy", ready.lines().first())
        assertEquals(
            setOf("catalog: unhealthy - cannot read products.txt", "storage: healthy"),
            ready.lines().drop(1).toSet(),
        )
        assertFalse("secret detail" in ready.body, ready.body)
        val live = fetch(port, "/health/live")
        assertEquals(200, live.status)
        assertEquals(listOf("ACTIVE healthy"), live.lines())
    }

    @Test fun `a check that throws is reported without the exception text`() = healthTest({
        readiness("broken") { throw IllegalStateException("secret detail") }
    }) { port ->
        val ready = fetch(port, "/health/ready")
        assertEquals(503, ready.status)
        assertEquals(listOf("ACTIVE unhealthy", "broken: unhealthy"), ready.lines())
    }

    @Test fun `a check that throws an Error answers 503 without the error text`() = healthTest({
        readiness("broken") { TODO("secret detail") }
    }) { port ->
        val ready = fetch(port, "/health/ready")
        assertEquals(503, ready.status)
        assertEquals(listOf("ACTIVE unhealthy"), ready.lines())
    }

    @Test fun `a message with line breaks stays on one line`() = healthTest({
        readiness("catalog") { Health.unhealthy("first\nsecond\r\nthird") }
    }) { port ->
        assertEquals(
            listOf("ACTIVE unhealthy", "catalog: unhealthy - first second third"),
            fetch(port, "/health/ready").lines(),
        )
    }

    @Test fun `health endpoints without a server definition leave the application alone`() =
        serverTest(module { healthEndpoints() }) { app ->
            app.start()
            app.stop()
            assertEquals(ApplicationStatus.STOPPED, app.status)
        }
}
