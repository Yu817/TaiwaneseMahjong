package com.mahjongplay.display

import org.bukkit.util.Vector

object TileAimResolver {
    fun findOnHandPlane(
        origin: Vector,
        direction: Vector,
        planePoint: Vector,
        planeNormal: Vector,
        horizontalAxis: Vector,
        centers: List<Vector>,
        maxDistance: Double,
        tileHalfWidth: Double,
        verticalTolerance: Double,
    ): Int? {
        if (direction.lengthSquared() == 0.0 || planeNormal.lengthSquared() == 0.0 || horizontalAxis.lengthSquared() == 0.0 ||
            tileHalfWidth <= 0.0 || verticalTolerance <= 0.0
        ) {
            return null
        }

        val normalizedDirection = direction.clone().normalize()
        val normalizedNormal = planeNormal.clone().normalize()
        val normalizedHorizontal = horizontalAxis.clone().normalize()
        val denominator = normalizedDirection.dot(normalizedNormal)
        if (kotlin.math.abs(denominator) < 1.0e-6) return null

        val distance = planePoint.clone().subtract(origin).dot(normalizedNormal) / denominator
        if (distance <= 0.0 || distance > maxDistance) return null
        val hitPoint = origin.clone().add(normalizedDirection.multiply(distance))

        return centers.mapIndexedNotNull { index, center ->
            val delta = hitPoint.clone().subtract(center)
            val horizontalDistance = kotlin.math.abs(delta.dot(normalizedHorizontal))
            val verticalDistance = kotlin.math.abs(delta.y)
            if (horizontalDistance > tileHalfWidth || verticalDistance > verticalTolerance) {
                return@mapIndexedNotNull null
            }
            val normalizedDistance =
                horizontalDistance / tileHalfWidth + verticalDistance / verticalTolerance
            index to normalizedDistance
        }.minByOrNull { it.second }?.first
    }

}

class AimStabilizer(private val stabilityMillis: Long) {
    private var candidate: Int? = null
    private var candidateSince: Long = 0L

    fun update(index: Int?, nowMillis: Long): Int? {
        if (index == null) {
            candidate = null
            candidateSince = nowMillis
            return null
        }
        if (candidate != index) {
            candidate = index
            candidateSince = nowMillis
            return null
        }
        return index.takeIf { nowMillis - candidateSince >= stabilityMillis }
    }
}
