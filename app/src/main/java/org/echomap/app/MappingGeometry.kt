package org.echomap.app

data class Hit(val x: Float, val y: Float, val z: Float, val strength: Float)

data class Direction(val x: Float, val y: Float, val z: Float) {
    fun hit(distance: Double, strength: Float) = Hit(
        (x * distance).toFloat(), (y * distance).toFloat(), (z * distance).toFloat(), strength
    )

    companion object {
        // Rotation matrices map device axes to east, north and up. Follow the
        // top edge of the screen, including landscape and upside-down use.
        fun screenTop(rotation: FloatArray, quarterTurns: Int): Direction = when (quarterTurns) {
            1 -> Direction(-rotation[0], -rotation[3], -rotation[6])
            2 -> Direction(-rotation[1], -rotation[4], -rotation[7])
            3 -> Direction(rotation[0], rotation[3], rotation[6])
            else -> Direction(rotation[1], rotation[4], rotation[7])
        }
    }
}
