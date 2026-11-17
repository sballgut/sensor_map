package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RoutePlaybackTest {
    private fun node(id: String, x: Double, y: Double) = Node(id, Point(x,y), id)
    private val route = Route(listOf(node("a",0.0,0.0),node("b",0.0,10.0),node("c",10.0,10.0)),20.0)
    @Test fun interpolatesWalkingPosition() {
        val pose = routePose(route,5.0)
        assertEquals(Point(0.0,5.0),pose.position); assertEquals(15.0,pose.remaining,0.0)
        assertEquals("오른쪽으로 이동",pose.instruction); assertEquals(5.0,pose.nextDistance,0.0)
    }
    @Test fun advancesAtCorner() { val pose=routePose(route,10.0); assertEquals(1,pose.segment); assertEquals(PI/2,pose.heading,.001) }
    @Test fun clampsArrival() { val pose=routePose(route,100.0); assertTrue(pose.arrived); assertEquals(Point(10.0,10.0),pose.position); assertEquals(0.0,pose.remaining,0.0) }
    @Test fun handlesSameStartAndDestination() { assertTrue(routePose(Route(listOf(node("a",0.0,0.0)),0.0),0.0).arrived) }
    @Test fun clampsNegativeProgress() { assertEquals(Point(0.0,0.0),routePose(route,-10.0).position) }
    @Test fun shortestAngleAcrossNorth() { assertEquals(Math.toRadians(2.0),angleDelta(Math.toRadians(359.0),Math.toRadians(1.0)),.0001) }
    @Test fun smoothingDoesNotSnap() { val angle=smoothHeading(0.0,PI/2,.016); assertTrue(angle>0 && angle<PI/2) }
    @Test fun straightVerticesDoNotCreateFalseTurn() {
        val straight=Route(listOf(node("a",0.0,0.0),node("b",0.0,10.0),node("c",0.0,20.0),node("d",-10.0,20.0)),30.0)
        val pose=routePose(straight,5.0)
        assertEquals(15.0,pose.nextDistance,.001); assertEquals("왼쪽으로 이동",pose.instruction)
    }
    @Test fun zeroLengthSegmentIsSkipped() {
        val repeated=Route(listOf(node("a",0.0,0.0),node("b",0.0,0.0),node("c",10.0,0.0)),10.0)
        assertEquals(Point(5.0,0.0),routePose(repeated,5.0).position)
    }
}
