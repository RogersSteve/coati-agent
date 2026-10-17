package bot.coati.fixture.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the repeat-mode setup that scripts/device_bench/marketplace.py checks tasks R1-R4 against:
 * every configured ID exists, the work items are spread down the list, and the price drop happens
 * only from the configured refresh on.
 */
class RepeatModeTest {
    private val catalog = MarketCatalog.parse(File("src/main/assets/${MarketCatalog.ASSET}").readText())
    private val config = RepeatConfig.parse(File("src/main/assets/${RepeatConfig.ASSET}").readText())
    private val ids = catalog.listings.map { it.id }

    @Test
    fun `every configured listing exists and the work items need scrolling`() {
        assertTrue(ids.containsAll(config.compare))
        assertTrue(ids.containsAll(config.sold))
        assertTrue(config.watchId in ids)
        assertEquals(6, config.compare.toSet().size)
        assertEquals(6, config.sold.size)
        // Both sets reach past the first screen (about 6 rows) to near the end of the list.
        assertTrue(config.compare.maxOf { ids.indexOf(it) } >= 35)
        assertTrue(config.sold.maxOf { ids.indexOf(it) } >= 35)
        // The one listing sold in the catalog is sold in repeat mode too.
        assertTrue(catalog.listings.filter { it.status == "Sold" }.all { config.isSold(it.id) })
    }

    @Test
    fun `the watched price drops below the threshold on the configured refresh and stays down`() {
        val watched = catalog.byId(config.watchId)!!
        assertTrue(watched.priceUsd >= 40_000)
        assertTrue(config.newPriceUsd < 40_000)
        // The reported price identifies the watched listing: no other listing has it.
        assertTrue(catalog.listings.none { it.priceUsd == config.newPriceUsd })
        assertEquals(5, config.dropAtRefresh)
        assertEquals(watched.priceUsd, config.priceUsd(watched, config.dropAtRefresh - 1))
        assertEquals(config.newPriceUsd, config.priceUsd(watched, config.dropAtRefresh))
        assertEquals(config.newPriceUsd, config.priceUsd(watched, config.dropAtRefresh + 3))
        val other = catalog.listings.first { it.id != config.watchId }
        assertEquals(other.priceUsd, config.priceUsd(other, 10))
    }

    @Test
    fun `favorites and compare toggle, hidden rows leave the list`() {
        assertEquals(setOf("CM-1"), emptySet<String>().toggled("CM-1"))
        assertEquals(emptySet<String>(), setOf("CM-1").toggled("CM-1"))
        val state = RepeatState(hidden = config.sold)
        assertEquals(catalog.listings.size - config.sold.size, state.visible(catalog.listings).size)
        assertTrue(state.visible(catalog.listings).none { config.isSold(it.id) })
    }
}
