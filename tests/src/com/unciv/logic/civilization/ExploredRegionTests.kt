package com.unciv.logic.civilization

import com.unciv.logic.map.HexMath
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class ExploredRegionTests {
    private fun region(worldWrap: Boolean = true) = ExploredRegion().apply {
        setMapParameters(MapParameters().apply {
            this.worldWrap = worldWrap
            shape = MapShape.rectangular
            mapSize = MapSize(16, 12)
        })
    }

    private fun explore(region: ExploredRegion, columns: IntRange) {
        for (column in columns) {
            val position = HexMath.getTileCoordsFromColumnRow((column + 8).mod(16) - 8, 1)
            region.checkTilePosition(position, null)
        }
    }

    @Test
    fun cutStaysInTheUnknownGapAndMovesOnlyWhenNecessary() {
        val region = region()
        explore(region, 4..8)
        val cut = region.getWorldWrapCut()!!
        explore(region, 9..10)
        assertEquals(cut, region.getWorldWrapCut(cut))
        explore(region, 11..14)
        val next = region.getWorldWrapCut(cut)!!
        assertNotEquals(cut, next)
        val positions = (4..14).map { HexMath.getTileCoordsFromColumnRow((it + 8).mod(16) - 8, 1) }
        assertEquals(10, next.getColumn(positions.last()) - next.getColumn(positions.first()))
    }

    @Test
    fun horizontalCompletionUsesExistingRegionRule() {
        val region = region()
        explore(region, 4..18)
        assertNotNull(region.getWorldWrapCut())
        explore(region, 19..19)
        assertNull(region.getWorldWrapCut())
        region.calculateStageCoords(960f, 800f)
        assertFalse(region.shouldRestrictX())
    }

    @Test
    fun nonWrappedMapsHaveNoCut() {
        val region = region(worldWrap = false)
        explore(region, 4..6)
        assertNull(region.getWorldWrapCut())
    }
}
