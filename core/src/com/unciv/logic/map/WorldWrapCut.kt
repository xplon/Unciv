package com.unciv.logic.map

import yairm210.purity.annotations.Readonly
import kotlin.math.abs

/** An unfolded view of a wrapped map, starting just before [firstColumn].
 * Columns increase to the right and lie in `[firstColumn, firstColumn + width)`.
 * Manual movement may go around obstacles inside this interval, but must not take
 * a shortcut across its ends. This does not change the map's physical neighbors.
 */
data class WorldWrapCut(val firstColumn: Int, val width: Int) {
    @Readonly
    fun getColumn(position: HexCoord): Int =
        firstColumn + (HexMath.getColumn(position) - firstColumn).mod(width)

    /** Only meaningful for adjacent tiles. */
    @Readonly
    fun isCrossing(from: HexCoord, to: HexCoord): Boolean =
        abs(getColumn(from) - getColumn(to)) > 1
}
