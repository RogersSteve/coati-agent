package bot.coati.model

import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class ModelRouterTest {

    @Test
    fun `ModelRouter holds reasoning and fast gateways`() {
        val reasoning = mockk<CloudModelGateway>()
        val fast = mockk<CloudModelGateway>()
        val router = ModelRouter(reasoning, fast)

        assertSame(reasoning, router.reasoning)
        assertSame(fast, router.fast)
    }

    @Test
    fun `ModelRouter allows same gateway for both roles`() {
        val gateway = mockk<CloudModelGateway>()
        val router = ModelRouter(gateway, gateway)

        assertSame(router.reasoning, router.fast)
    }

    @Test
    fun `ModelRouter instances are independent`() {
        val r1 = mockk<CloudModelGateway>()
        val r2 = mockk<CloudModelGateway>()
        val f1 = mockk<CloudModelGateway>()
        val f2 = mockk<CloudModelGateway>()

        val router1 = ModelRouter(r1, f1)
        val router2 = ModelRouter(r2, f2)

        assertNotNull(router1)
        assertNotNull(router2)
        assertSame(r1, router1.reasoning)
        assertSame(r2, router2.reasoning)
    }
}
