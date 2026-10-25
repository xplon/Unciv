package com.unciv.logic.map

import com.unciv.UncivGame
import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.GameSettings.PathfindingAlgorithm
import com.unciv.testing.TestGame
import com.unciv.testing.TestRunnerFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(TestRunnerFactory::class)
class WorldWrapMovementTests(private val algorithm: PathfindingAlgorithm) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters
        fun parameters() = TestRunnerFactory.Parameters.pathfinding
    }

    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var unit: MapUnit
    private val rightwardCut = WorldWrapCut(0, 16)

    @Before
    fun setup() {
        game = TestGame()
        UncivGame.Current.settings.useAStarPathfinding = algorithm == PathfindingAlgorithm.AStarPathfinding
        game.tileMap.mapParameters.worldWrap = true
        game.makeRectangularMap(9, 16)
        game.tileMap.mapParameters.worldWrap = true
        game.tileMap.mapParameters.shape = MapShape.rectangular
        civ = game.addCiv(isPlayer = true)
        civ.exploredRegion.setMapParameters(game.tileMap.mapParameters)
        unit = game.addDefaultMeleeUnitWithUniques(civ, tile(0))
    }

    private fun tile(column: Int, row: Int = 0): Tile =
        game.tileMap[HexMath.getTileCoordsFromColumnRow((column + 8).mod(16) - 8, row)]

    private fun movement(cut: WorldWrapCut = rightwardCut): UnitMovement = unit.movement.withWorldWrapCut(cut)

    private fun revealMap() {
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun setMovement(points: Int) {
        unit.baseUnit.movement = points
        unit.currentMovement = points.toFloat()
        unit.movement.clearPathfindingCache()
    }

    @Test
    fun rightwardOrderDoesNotTakeLeftwardShortcut() {
        revealMap()
        assertEquals(listOf(tile(-1)), unit.movement.getShortestPath(tile(-1)))
        val firstTurn = movement().getShortestPath(tile(-1)).first()
        assertEquals(2, rightwardCut.getColumn(firstTurn.position))
        movement().headTowards(tile(-1))
        assertSame(firstTurn, unit.currentTile)
    }

    @Test
    fun leftwardOrderDoesNotTakeRightwardShortcut() {
        revealMap()
        val cut = WorldWrapCut(1, 16)
        val firstTurn = movement(cut).getShortestPath(tile(1)).first()
        assertEquals(14, cut.getColumn(firstTurn.position))
        movement(cut).headTowards(tile(1))
        assertSame(firstTurn, unit.currentTile)
    }

    @Test
    fun orderCanCrossOriginalCoordinateSeam() {
        revealMap()
        unit.removeFromTile()
        unit.putInTile(tile(7))
        movement().headTowards(tile(9))
        assertSame(tile(9), unit.currentTile)
    }

    @Test
    fun routeCanDetourAroundKnownObstacle() {
        revealMap()
        setMovement(6)
        game.setTileTerrain(tile(1).position, "Mountain")
        val destination = tile(3)
        val path = movement().getDistanceToTiles().getPathToTile(destination)
        assertFalse(path.contains(tile(1)))
        assertTrue(path.any { HexMath.getLatitude(it.position) != 0 })
        movement().headTowards(destination)
        assertSame(destination, unit.currentTile)
    }

    @Test
    fun rightwardRouteCanStartWithALeftwardDetour() {
        revealMap()
        setMovement(10)
        val exit = tile(-1)
        for (neighbor in unit.currentTile.neighbors) {
            if (neighbor != exit) game.setTileTerrain(neighbor.position, "Mountain")
        }
        val cut = WorldWrapCut(-4, 16)
        val path = movement(cut).getDistanceToTiles().getPathToTile(tile(4))
        assertSame(exit, path.first())
        movement(cut).headTowards(tile(4))
        assertSame(tile(4), unit.currentTile)
    }

    @Test
    fun manualAndOrdinaryCachesAreIsolated() {
        revealMap()
        val ordinary = unit.movement.getShortestPath(tile(-1))
        assertTrue(movement().getShortestPath(tile(-1)).size > ordinary.size)
        assertEquals(ordinary, unit.movement.getShortestPath(tile(-1)))
        assertEquals(ordinary, movement(WorldWrapCut(8, 16)).getShortestPath(tile(-1)))
        assertTrue(movement().getShortestPath(tile(-1)).size > ordinary.size)
    }

    @Test
    fun unknownMountainStopsMovementWithoutRemovingUnit() {
        setMovement(12)
        for (tile in game.tileMap.values) {
            if (rightwardCut.getColumn(tile.position) == 9)
                game.setTileTerrain(tile.position, "Mountain")
        }
        assertTrue("Preview must not disclose the unknown mountain wall", movement().getShortestPath(tile(12)).isNotEmpty())
        movement().headTowards(tile(12))
        assertTrue(rightwardCut.getColumn(unit.currentTile.position) < 9)
        assertSame(unit, unit.currentTile.militaryUnit)
    }

    @Test
    fun unknownHillChargesRealMovementCost() {
        setMovement(12)
        val hill = game.setTileTerrainAndFeatures(tile(9).position, "Grassland", "Hill")
        assertFalse(civ.hasExplored(hill))
        assertEquals(9f, movement().getDistanceToTiles().getValue(hill).totalMovement, 0.001f)
        movement().headTowards(hill)
        assertSame(hill, unit.currentTile)
        assertEquals(2f, unit.currentMovement, 0.001f)
        assertSame(unit, hill.militaryUnit)
    }

    @Test
    fun newlyRevealedTerrainInvalidatesManualPathEstimate() {
        setMovement(12)
        val wall = game.tileMap.values.filter { rightwardCut.getColumn(it.position) == 9 }
        for (tile in wall) game.setTileTerrain(tile.position, "Mountain")
        val manualMovement = movement()
        val destination = tile(12)
        assertTrue(manualMovement.getShortestPath(destination).isNotEmpty())
        assertTrue(manualMovement.getDistanceToTiles().containsKey(destination))

        for (tile in wall) tile.setExplored(civ, true)
        assertTrue(manualMovement.getShortestPath(destination).isEmpty())
        assertFalse(manualMovement.getDistanceToTiles().containsKey(destination))
    }

    @Test
    fun multiTurnOrderKeepsItsDirectionAndStopsAtRevealedObstacle() {
        for (tile in game.tileMap.values) {
            if (rightwardCut.getColumn(tile.position) == 9)
                game.setTileTerrain(tile.position, "Mountain")
        }
        unit.setMoveToAction(tile(-1), rightwardCut)
        var previousColumn = 0
        repeat(8) {
            if (!unit.isMoving()) return@repeat
            unit.currentMovement = 2f
            unit.addMovementMemory()
            unit.doAction()
            val column = rightwardCut.getColumn(unit.currentTile.position)
            assertTrue("A later turn must not switch to the opposite shortcut", column >= previousColumn)
            assertTrue(column < 9)
            assertSame(unit, unit.currentTile.militaryUnit)
            previousColumn = column
        }
        assertFalse(unit.isMoving())
    }

    @Test
    fun savedOrderRetainsCutAndOlderOrdersRemainReadable() {
        val destination = tile(-1)
        unit.action = "moveTo ${destination.position.x.toFloat()},${destination.position.y.toFloat()}"
        assertSame(destination, unit.getMovementDestination())
        assertNull(unit.getMovementWorldWrapCut())
        unit.setMoveToAction(destination, rightwardCut)
        val restored = json().fromJson(MapUnit::class.java, json().toJson(unit))
        restored.civ = civ
        restored.currentTile = unit.currentTile
        assertSame(destination, restored.getMovementDestination())
        assertEquals(rightwardCut, restored.getMovementWorldWrapCut())
        restored.setTransients(game.ruleset)
        unit.removeFromTile()
        civ.units.removeUnit(unit)
        civ.units.addUnit(restored)
        restored.putInTile(tile(0))
        restored.addMovementMemory()
        restored.doAction()
        assertEquals(2, rightwardCut.getColumn(restored.currentTile.position))
        assertSame(restored, restored.currentTile.militaryUnit)
        unit.setMoveToAction(tile(2))
        assertNull(unit.getMovementWorldWrapCut())
    }
}
