package bot.coati.fixture.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the ground truth that scripts/device_bench/marketplace.py scores against, so an edit to
 * listings.json that changes an expected answer fails here as well as in the Python tests.
 */
class MarketCatalogTest {
    private val catalog = MarketCatalog.parse(File("src/main/assets/${MarketCatalog.ASSET}").readText())

    private fun Measure.miles() = if (unit == "km") value / KM_PER_MILE else value.toDouble()
    private fun Measure.km() = if (unit == "mi") value * KM_PER_MILE else value.toDouble()

    /** The benchmark query: available 2023 Mercedes-Benz E 300, within 100 km of Los Angeles, under 50,000 miles. */
    private val matches = catalog.listings.filter {
        it.status == "Available" && it.make == "Mercedes-Benz" && it.model == "E 300" && it.year == 2023 &&
            it.distance.km() <= 100 && it.mileage.miles() < 50_000
    }

    @Test
    fun `catalog has forty unique listings that need scrolling`() {
        assertEquals(40, catalog.listings.size)
        assertEquals(40, catalog.listings.map { it.id }.toSet().size)
        assertTrue(catalog.listings.all { Regex("CM-\\d{4}").matches(it.id) })
        assertEquals("Los Angeles", catalog.referenceCity)
    }

    @Test
    fun `list rows hide every decisive field`() {
        catalog.listings.forEach {
            assertFalse(it.listTitle, it.year.toString() in it.listTitle)
            assertFalse(it.listTitle, it.model in it.listTitle)
        }
        assertEquals(16, catalog.listings.count { it.family.startsWith("E-Class") })
        // The last row is a match, so a complete scan has to reach the bottom of the list.
        assertEquals("CM-9046", catalog.listings.last().id)
    }

    @Test
    fun `expected answers`() {
        assertEquals(listOf("CM-3417", "CM-4052", "CM-1586", "CM-8134", "CM-9046"), matches.map { it.id })
        assertEquals(
            listOf("CM-3417", "CM-4052", "CM-8134", "CM-9046"),
            matches.filter { it.accidents == "None reported" }.map { it.id },
        )
        val cheapest = matches.minBy { it.priceUsd }
        assertEquals("CM-1586" to "$39,950", cheapest.id to cheapest.priceLabel)
    }

    @Test
    fun `near misses fail exactly one criterion`() {
        val sold = catalog.byId("CM-6375")!!
        assertEquals("Sold", sold.status)
        assertEquals(catalog.byId("CM-9046")!!.copy(id = sold.id, status = "Sold", priceUsd = sold.priceUsd, note = sold.note), sold)
        assertEquals("50,500 mi", catalog.byId("CM-6943")!!.mileage.label)
        assertEquals("49,800 mi", catalog.byId("CM-1586")!!.mileage.label)
        assertEquals("64,000 km", catalog.byId("CM-8134")!!.mileage.label)
        assertEquals("101 km", catalog.byId("CM-7720")!!.distance.label)
        assertEquals("68 mi", catalog.byId("CM-2297")!!.distance.label)
        assertEquals("E 350", catalog.byId("CM-5208")!!.model)
        assertEquals(2022, catalog.byId("CM-2861")!!.year)
        // Two non-matches undercut the cheapest match, so a careless cheapest answer is wrong.
        val cheaperTraps = catalog.listings.filter { it.family == "E-Class Sedan" && it.priceUsd < 39_950 }
        assertEquals(setOf("CM-2861", "CM-6943"), cheaperTraps.map { it.id }.toSet())
    }

    @Test
    fun `detail page shows units as entered`() {
        val lines = catalog.byId("CM-2297")!!.detailLines(catalog.referenceCity)
        assertTrue("Distance from Los Angeles: 68 mi" in lines)
        assertEquals("2023 Mercedes-Benz E 300", catalog.byId("CM-2297")!!.detailTitle)
        assertEquals("Mercedes-Benz E-Class Sedan", catalog.byId("CM-2297")!!.listTitle)
    }

    private companion object {
        const val KM_PER_MILE = 1.609344
    }
}
