package com.unciv.logic.map

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.civilization.Civilization
import com.unciv.models.tilesets.TileSetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.UncivActorGestureListener
import com.unciv.ui.components.tilegroups.TileGroupMap
import com.unciv.ui.components.tilegroups.WorldTileGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.minimap.Minimap
import com.unciv.ui.screens.worldscreen.minimap.MinimapTile
import com.unciv.ui.screens.worldscreen.worldmap.WorldMapHolder
import com.unciv.view.GameView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito

/** Real map and minimap actors; the surrounding screen and graphics batch are headless. */
@RunWith(GdxTestRunner::class)
class WorldWrapMapTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var view: GameView
    private lateinit var holder: WorldMapHolder
    private lateinit var map: TileGroupMap<WorldTileGroup>

    @Before
    fun setup() {
        game = TestGame()
        Fonts.fontImplementation = FontDesktop()
        ImageGetter.setNewRuleset(game.ruleset)
        TileSetCache.loadTileSetConfigs()
    }

    private fun tile(column: Int) = game.tileMap[HexMath.getTileCoordsFromColumnRow((column + 8).mod(16) - 8, 0)]

    private fun reveal(columns: IntRange) {
        for (column in columns) tile(column).setExplored(civ, true)
    }

    private fun invokeInternal(owner: Any, name: String, vararg args: Any?) = owner.javaClass.declaredMethods
        .first { it.name.startsWith(name) && it.parameterCount == args.size }
        .apply { isAccessible = true }.invoke(owner, *args)

    private fun readField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun createMap(known: IntRange, worldWrap: Boolean = true, spectator: Boolean = false, hexagonal: Boolean = false) {
        if (hexagonal) {
            game.gameInfo.tileMap = TileMap(8, game.ruleset, worldWrap)
            game.tileMap.gameInfo = game.gameInfo
            game.tileMap.mapParameters.mapSize = MapSize(8)
        } else {
            game.tileMap.mapParameters.worldWrap = worldWrap
            game.makeRectangularMap(12, 16)
            game.tileMap.mapParameters.shape = MapShape.rectangular
        }
        game.tileMap.mapParameters.worldWrap = worldWrap
        civ = game.addCiv(isPlayer = true)
        civ.exploredRegion.setMapParameters(game.tileMap.mapParameters)
        reveal(known)
        view = GameView(game.gameInfo, civ, spectatorMode = spectator)
        createHolder()
    }

    private fun createHolder() {
        val screen = Mockito.mock(WorldScreen::class.java)
        val stage = Mockito.mock(Stage::class.java)
        Mockito.`when`(stage.width).thenReturn(1200f)
        Mockito.`when`(stage.height).thenReturn(800f)
        Mockito.`when`(screen.stage).thenReturn(stage)
        Mockito.`when`(screen.game).thenReturn(UncivGame.Current)
        Mockito.`when`(screen.gameInfo).thenReturn(game.gameInfo)
        Mockito.`when`(screen.selectedGameView).thenReturn(view)
        holder = WorldMapHolder(screen, game.tileMap)
        invokeInternal(holder, "addTiles")
        @Suppress("UNCHECKED_CAST")
        map = holder.actor as TileGroupMap<WorldTileGroup>
        for (group in holder.tileGroups.values) group.update(view.civView)
    }

    private fun drawMap() {
        val batch = Mockito.mock(Batch::class.java)
        Mockito.`when`(batch.transformMatrix).thenReturn(Matrix4())
        Mockito.`when`(batch.projectionMatrix).thenReturn(Matrix4())
        Mockito.`when`(batch.color).thenReturn(Color.WHITE.cpy())
        map.draw(batch, 1f)
    }

    @Test
    fun panningAndZoomingDoNotMovePreviewOrKnownTilesToAnotherCopy() {
        createMap(3..15)
        assertNotNull(civ.exploredRegion.getWorldWrapCut())
        assertFalse(holder.continuousScrollingX)
        assertEquals(game.tileMap.values.size, holder.tileGroups.size)
        val positions = holder.tileGroups.values.associateWith { it.x }
        val target = holder.tileGroups.getValue(view.tileMapView.getTile(tile(2)))
        target.layerOverlay.showHighlight(Color.SKY, 0.8f)
        val button = Actor().apply { setSize(60f, 60f) }
        holder.addOverlayOnTileGroup(target, button)
        val buttonX = button.x
        for (zoom in listOf(1f, 0.5f, 2f)) {
            holder.zoom(zoom)
            for (x in listOf(0f, holder.maxX, holder.maxX / 2)) {
                holder.scrollX = x
                drawMap()
                for ((group, originalX) in positions) assertEquals(originalX, group.x, 0.001f)
                assertEquals(buttonX, button.x, 0.001f)
            }
        }
    }

    @Test
    fun clickingBeyondTheDisplayedIntervalCannotSelectTheKnownTileOnTheOtherSide() {
        createMap(3..15)
        val known = holder.tileGroups.getValue(view.tileMapView.getTile(tile(4)))
        val listener = map.listeners.first { it is UncivActorGestureListener } as UncivActorGestureListener
        for (offset in listOf(-map.width, map.width)) {
            val x = known.x + known.width / 2 + offset
            val y = known.y + known.height / 2
            assertNull(map.hit(x, y, true))
            for (button in listOf(0, 1)) listener.tap(null, x, y, 1, button)
            assertNull(readField(holder, "selectedTile"))
        }
    }

    @Test
    fun knownRegionIsContinuousAcrossTheOriginalSeam() {
        createMap(4..10)
        for (column in 4..9) {
            val left = holder.tileGroups.getValue(view.tileMapView.getTile(tile(column)))
            val right = holder.tileGroups.getValue(view.tileMapView.getTile(tile(column + 1)))
            assertEquals(60f, right.x - left.x, 0.001f)
        }
    }

    @Test
    fun movingCutPreservesKnownPositionsRelativeToCamera() {
        createMap(4..8)
        val anchor = holder.tileGroups.getValue(view.tileMapView.getTile(tile(6)))
        holder.scrollX = anchor.x + anchor.width / 2
        val before = anchor.x - holder.scrollX
        val oldCut = civ.exploredRegion.getWorldWrapCut()!!
        reveal(9..(oldCut.firstColumn + if (oldCut.firstColumn < 9) 16 else 0))
        val newCut = civ.exploredRegion.getWorldWrapCut(oldCut)
        assertNotNull(newCut)
        assertNotEquals(oldCut, newCut)
        invokeInternal(holder, "updateWorldWrap", view.civView)
        assertEquals(before, anchor.x - holder.scrollX, 0.001f)
        assertEquals(game.tileMap.values.size, holder.tileGroups.size)
    }

    @Test
    fun animatedScrollKeepsItsLogicalTargetWhenCutMoves() {
        createMap(4..8)
        val from = holder.tileGroups.getValue(view.tileMapView.getTile(tile(6)))
        val target = holder.tileGroups.getValue(view.tileMapView.getTile(tile(7)))
        holder.scrollX = from.x + from.width / 2
        holder.scrollTo(target.x + target.width / 2, holder.scrollY)
        holder.act(0.1f)
        val relativeX = from.x - holder.scrollX

        reveal(9..14)
        invokeInternal(holder, "updateWorldWrap", view.civView)
        assertEquals(relativeX, from.x - holder.scrollX, 0.001f)
        holder.act(0.5f)
        assertEquals(target.x + target.width / 2, holder.scrollX, 0.001f)
    }

    @Test
    fun screenRestoreKeepsCameraPositionWhenNewHolderChoosesAnotherCut() {
        createMap(4..8)
        reveal(9..10)
        invokeInternal(holder, "updateWorldWrap", view.civView)
        val anchor = holder.tileGroups.getValue(view.tileMapView.getTile(tile(6)))
        holder.scrollX = anchor.x + anchor.width / 2
        val relativeX = anchor.x - holder.scrollX
        val oldCut = readField(holder, "worldWrapCut")
        val state = WorldScreen.RestoreState(holder, civ.civID, civ.civID, false)

        createHolder()
        assertNotEquals(oldCut, readField(holder, "worldWrapCut"))
        invokeInternal(holder, "setMapScrollX", state.mapScrollX)
        holder.updateVisualScroll()
        val restoredAnchor = holder.tileGroups.getValue(view.tileMapView.getTile(tile(6)))
        assertEquals(relativeX, restoredAnchor.x - holder.scrollX, 0.001f)

        reveal(11..19)
        createHolder()
        assertNull(readField(holder, "worldWrapCut"))
        invokeInternal(holder, "setMapScrollX", state.mapScrollX)
        val completedAnchor = holder.tileGroups.getValue(view.tileMapView.getTile(tile(6)))
        assertEquals(relativeX, completedAnchor.x - holder.scrollX, 0.001f)
    }

    @Test
    fun pendingOrderKeepsItsSideWhenExplorationMovesTheDisplayedTarget() {
        createMap(4..8)
        val unit = game.addDefaultMeleeUnitWithUniques(civ, tile(6))
        val unitView = view.getMapUnitView(unit)
        val orderCut = civ.exploredRegion.getWorldWrapCut()!!
        unit.setMoveToAction(tile(0), orderCut)
        assertEquals(true, invokeInternal(holder, "isMovementDestinationOnDisplayedCopy", unitView))

        reveal(9..14)
        invokeInternal(holder, "updateWorldWrap", view.civView)
        assertNotEquals(orderCut, readField(holder, "worldWrapCut"))
        assertEquals(false, invokeInternal(holder, "isMovementDestinationOnDisplayedCopy", unitView))

        unit.addMovementMemory()
        unit.doAction()
        assertEquals(4, orderCut.getColumn(unit.currentTile.position).mod(16))
        assertEquals(orderCut, unit.getMovementWorldWrapCut())
    }

    @Test
    fun completedHorizontalExplorationRestoresContinuousWrapping() {
        createMap(4..8)
        assertFalse(holder.continuousScrollingX)
        val target = holder.tileGroups.getValue(view.tileMapView.getTile(tile(7)))
        holder.scrollX = target.x - 60f
        holder.scrollTo(target.x + target.width / 2, holder.scrollY)
        reveal(9..19)
        assertNull(civ.exploredRegion.getWorldWrapCut())
        invokeInternal(holder, "updateWorldWrap", view.civView)
        assertTrue(holder.continuousScrollingX)
        holder.act(0.5f)
        assertEquals(target.x + target.width / 2, holder.scrollX, 0.001f)
        val positions = holder.tileGroups.values.map { it.x }
        holder.scrollX = holder.maxX
        drawMap()
        assertNotEquals(positions, holder.tileGroups.values.map { it.x })
    }

    @Test
    fun minimapCameraTracksKnownTileAcrossOriginalSeam() {
        createMap(4..8)
        val minimap = Minimap(holder, 15, civ)
        val target = holder.tileGroups.getValue(view.tileMapView.getTile(tile(6)))
        holder.onViewportChangedListener!!.invoke(holder.maxX, holder.maxY,
            Rectangle(target.x + target.width / 2 - 300f, target.y - 200f, 600f, 400f))
        @Suppress("UNCHECKED_CAST")
        val tiles = readField(minimap, "minimapTiles") as List<MinimapTile>
        @Suppress("UNCHECKED_CAST")
        val indicators = readField(minimap, "scrollPositionIndicators") as List<Actor>
        val image = tiles.first { it.tile === tile(6) }.image
        assertEquals(image.x + image.width / 2, indicators.first().x + indicators.first().width / 2, image.width)
    }

    @Test
    fun hexagonalWrappedMapAlsoHasOneStableCopyPerTile() {
        createMap(4..10, hexagonal = true)
        assertFalse(holder.continuousScrollingX)
        val positions = holder.tileGroups.values.map { it.x }
        holder.scrollX = holder.maxX
        drawMap()
        assertEquals(positions, holder.tileGroups.values.map { it.x })
    }

    @Test
    fun nonWrappedMapKeepsItsOriginalLayout() {
        createMap(3..6, worldWrap = false)
        assertFalse(holder.continuousScrollingX)
        assertNull(readField(holder, "worldWrapCut"))
    }

    @Test
    fun spectatorKeepsContinuousWrapping() {
        createMap(3..6, spectator = true)
        assertTrue(holder.continuousScrollingX)
        assertNull(readField(holder, "worldWrapCut"))
    }
}
