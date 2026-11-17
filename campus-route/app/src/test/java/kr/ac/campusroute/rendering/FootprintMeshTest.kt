package kr.ac.campusroute.rendering

import kr.ac.campusroute.domain.model.Point
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class FootprintMeshTest {
    private fun area(p: List<Point>): Double = abs(p.indices.sumOf { i -> val a=p[i]; val b=p[(i+1)%p.size]; a.x*b.y-b.x*a.y }) / 2
    private fun verify(points: List<Point>) {
        val triangles=triangulateFootprint(points)
        assertTrue(triangles.isNotEmpty())
        val meshArea=triangles.chunked(3).sumOf { ids -> area(ids.map { points[it] }) }
        assertEquals(area(points),meshArea,.00001)
    }
    @Test fun concaveLShapedBuildingHasNoFilledCourtyard() { verify(listOf(Point(0.0,0.0),Point(4.0,0.0),Point(4.0,1.0),Point(1.0,1.0),Point(1.0,4.0),Point(0.0,4.0))) }
    @Test fun clockwiseOutlineWorks() { verify(listOf(Point(0.0,0.0),Point(0.0,3.0),Point(2.0,3.0),Point(2.0,0.0))) }
    @Test fun collinearVerticesDoNotBreakRoof() { verify(listOf(Point(0.0,0.0),Point(1.0,0.0),Point(2.0,0.0),Point(2.0,2.0),Point(0.0,2.0))) }
    @Test fun insufficientPointsProduceNoTriangles() { assertTrue(triangulateFootprint(listOf(Point(0.0,0.0),Point(1.0,1.0))).isEmpty()) }
}
