package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Projects raw leg observations into local East-North meters around the leg midpoint, smooths noisy
 * GNSS points with velocity-gated accuracy weights, and simplifies with Douglas-Peucker while
 * retaining raw observations separately.
 */
internal object RouteSmoother {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEFAULT_EPSILON_METERS = 5.0
    private const val PINNED_TURN_DEGREES = 35.0
    private const val PINNED_TURN_MAX_ACCURACY_METERS = 25f
    private const val MAX_WALK_LATERAL_ACCEL_MPS2 = 6.0
    private const val MAX_VEHICLE_LATERAL_ACCEL_MPS2 = 12.0

    fun smooth(
        route: List<LocationObservation>,
        mode: TravelMode,
        epsilonMeters: Double = DEFAULT_EPSILON_METERS,
    ): List<LocationObservation> {
        if (route.size <= 2) return route
        val refLat = (route.first().latitude + route.last().latitude) / 2.0
        val refLon = (route.first().longitude + route.last().longitude) / 2.0
        val cosRefLat = cos(refLat * PI / 180.0).coerceAtLeast(1e-6)

        val enu =
            route.map { point ->
                toLocalEnu(point.latitude, point.longitude, refLat, refLon, cosRefLat)
            }
        val gated = rejectLateralOutliers(route, enu, mode)
        val filteredEnu = kalmanPass(gated.map { it.first }, gated.map { it.second })
        val pinned = computePinnedVertices(gated.map { it.first }, filteredEnu)
        val simplifiedIndices = douglasPeuckerIndices(filteredEnu, epsilonMeters, pinned)

        return simplifiedIndices.map { index ->
            val obs = gated[index].first
            if (index == 0 || index == gated.lastIndex) {
                obs
            } else {
                val (lat, lon) = fromLocalEnu(filteredEnu[index], refLat, refLon, cosRefLat)
                obs.copy(latitude = lat, longitude = lon)
            }
        }
    }

    fun pathLengthMeters(route: List<LocationObservation>): Double = route.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    private fun rejectLateralOutliers(
        route: List<LocationObservation>,
        enu: List<EnuPoint>,
        mode: TravelMode,
    ): List<Pair<LocationObservation, EnuPoint>> {
        if (route.size <= 2) return route.zip(enu)
        val maxLateralAccel =
            when (mode) {
                TravelMode.WALKING, TravelMode.RUNNING -> MAX_WALK_LATERAL_ACCEL_MPS2
                else -> MAX_VEHICLE_LATERAL_ACCEL_MPS2
            }
        val kept = mutableListOf(route.first() to enu.first())
        for (i in 1 until route.lastIndex) {
            val prevObs = kept.last().first
            val prevEnu = kept.last().second
            val currObs = route[i]
            val currEnu = enu[i]
            val nextObs = route[i + 1]
            val nextEnu = enu[i + 1]

            val dt1 = ((currObs.timestamp - prevObs.timestamp).inWholeMilliseconds / 1000.0).coerceAtLeast(1.0)
            val dt2 = ((nextObs.timestamp - currObs.timestamp).inWholeMilliseconds / 1000.0).coerceAtLeast(1.0)
            val vx1 = (currEnu.east - prevEnu.east) / dt1
            val vy1 = (currEnu.north - prevEnu.north) / dt1
            val vx2 = (nextEnu.east - currEnu.east) / dt2
            val vy2 = (nextEnu.north - currEnu.north) / dt2
            val dtAvg = ((dt1 + dt2) / 2.0).coerceAtLeast(1.0)
            val lateralAccel = hypot(vx2 - vx1, vy2 - vy1) / dtAvg
            val perpendicularDeviation = perpendicularDistance(currEnu, prevEnu, nextEnu)
            val allowedDeviation = maxOf(25.0, (currObs.accuracyMeters ?: 25f).toDouble() * 1.5)
            val isAccuracyDegraded = (currObs.accuracyMeters ?: 25f) > 50f

            if (lateralAccel > maxLateralAccel && perpendicularDeviation > allowedDeviation && isAccuracyDegraded) {
                continue
            }
            kept += currObs to currEnu
        }
        kept += route.last() to enu.last()
        return kept
    }

    private fun kalmanPass(
        observations: List<LocationObservation>,
        enu: List<EnuPoint>,
    ): List<EnuPoint> {
        if (observations.size <= 2) return enu
        val result = ArrayList<EnuPoint>(observations.size)
        var estEast = enu.first().east
        var estNorth = enu.first().north
        var velEast = 0.0
        var velNorth = 0.0
        var varPos = accuracyVariance(observations.first().accuracyMeters)
        result += enu.first()

        for (i in 1 until observations.size) {
            val dt = ((observations[i].timestamp - observations[i - 1].timestamp).inWholeMilliseconds / 1000.0).coerceIn(0.5, 120.0)
            val predEast = estEast + velEast * dt
            val predNorth = estNorth + velNorth * dt
            val processVar = dt * 4.0
            val predVar = varPos + processVar

            val measVar = accuracyVariance(observations[i].accuracyMeters)
            val gain = predVar / (predVar + measVar)

            val measEast = enu[i].east
            val measNorth = enu[i].north
            val newEast = predEast + gain * (measEast - predEast)
            val newNorth = predNorth + gain * (measNorth - predNorth)

            velEast = (newEast - estEast) / dt
            velNorth = (newNorth - estNorth) / dt
            estEast = newEast
            estNorth = newNorth
            varPos = (1.0 - gain) * predVar

            result +=
                if (i == observations.lastIndex) {
                    enu.last()
                } else {
                    EnuPoint(estEast, estNorth)
                }
        }
        return result
    }

    private fun accuracyVariance(accuracyMeters: Float?): Double {
        val sigma = max(accuracyMeters ?: 25f, 5f).toDouble()
        return sigma * sigma
    }

    private fun computePinnedVertices(
        observations: List<LocationObservation>,
        enu: List<EnuPoint>,
    ): BooleanArray {
        val pinned = BooleanArray(enu.size)
        if (enu.isEmpty()) return pinned
        pinned[0] = true
        pinned[enu.lastIndex] = true
        for (i in 1 until enu.lastIndex) {
            val acc = observations[i].accuracyMeters ?: continue
            if (acc > PINNED_TURN_MAX_ACCURACY_METERS) continue
            val angle = turnAngleDegrees(enu[i - 1], enu[i], enu[i + 1])
            if (angle > PINNED_TURN_DEGREES) {
                pinned[i] = true
            }
        }
        return pinned
    }

    private fun turnAngleDegrees(
        a: EnuPoint,
        b: EnuPoint,
        c: EnuPoint,
    ): Double {
        val v1x = b.east - a.east
        val v1y = b.north - a.north
        val v2x = c.east - b.east
        val v2y = c.north - b.north
        val m1 = hypot(v1x, v1y)
        val m2 = hypot(v2x, v2y)
        if (m1 < 1e-3 || m2 < 1e-3) return 0.0
        val cosTheta = ((v1x * v2x + v1y * v2y) / (m1 * m2)).coerceIn(-1.0, 1.0)
        return acos(cosTheta) * 180.0 / PI
    }

    private fun douglasPeuckerIndices(
        points: List<EnuPoint>,
        epsilonMeters: Double,
        pinned: BooleanArray,
    ): List<Int> {
        if (points.size <= 2) return points.indices.toList()
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        for (i in points.indices) {
            if (pinned[i]) keep[i] = true
        }

        val anchors = keep.indices.filter { keep[it] }
        for ((segmentStart, segmentEnd) in anchors.zipWithNext()) {
            simplifyRange(points, segmentStart, segmentEnd, epsilonMeters, keep)
        }
        return keep.indices.filter { keep[it] }
    }

    private fun simplifyRange(
        points: List<EnuPoint>,
        start: Int,
        end: Int,
        epsilonMeters: Double,
        keep: BooleanArray,
    ) {
        if (end - start <= 1) return
        var maxDist = -1.0
        var maxIndex = -1
        for (i in (start + 1) until end) {
            val dist = perpendicularDistance(points[i], points[start], points[end])
            if (dist > maxDist) {
                maxDist = dist
                maxIndex = i
            }
        }
        if (maxDist > epsilonMeters && maxIndex != -1) {
            keep[maxIndex] = true
            simplifyRange(points, start, maxIndex, epsilonMeters, keep)
            simplifyRange(points, maxIndex, end, epsilonMeters, keep)
        }
    }

    private fun perpendicularDistance(
        p: EnuPoint,
        a: EnuPoint,
        b: EnuPoint,
    ): Double {
        val dx = b.east - a.east
        val dy = b.north - a.north
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6) return hypot(p.east - a.east, p.north - a.north)
        val cross = abs((p.east - a.east) * dy - (p.north - a.north) * dx)
        return cross / kotlin.math.sqrt(lenSq)
    }

    private fun toLocalEnu(
        lat: Double,
        lon: Double,
        refLat: Double,
        refLon: Double,
        cosRefLat: Double,
    ): EnuPoint {
        val north = (lat - refLat) * (PI / 180.0) * EARTH_RADIUS_METERS
        val east = (lon - refLon) * (PI / 180.0) * EARTH_RADIUS_METERS * cosRefLat
        return EnuPoint(east, north)
    }

    private fun fromLocalEnu(
        point: EnuPoint,
        refLat: Double,
        refLon: Double,
        cosRefLat: Double,
    ): Pair<Double, Double> {
        val lat = refLat + (point.north / EARTH_RADIUS_METERS) * (180.0 / PI)
        val lon = refLon + (point.east / (EARTH_RADIUS_METERS * cosRefLat)) * (180.0 / PI)
        return lat to lon
    }

    private data class EnuPoint(
        val east: Double,
        val north: Double,
    )
}
