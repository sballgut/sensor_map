package kr.ac.campusroute.routing

import kr.ac.campusroute.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class BuildingLocationTest {
    private val building=Building("A",Point(0.0,0.0),20.0,20.0,10.0,listOf(Point(-10.0,-10.0),Point(10.0,-10.0),Point(10.0,10.0),Point(-10.0,10.0)),"a")
    private val campus=Campus("v","test",listOf(Node("x",Point(-20.0,0.0),"x"),Node("y",Point(20.0,0.0),"y")),listOf(Edge("x","y",40.0)),listOf(building))
    @Test fun detectsFootprintWithoutClaimingSensorIndoorState() { assertEquals(BuildingRelation.INSIDE_FOOTPRINT,locateBuilding(campus,Point(0.0,0.0),5.0).relation) }
    @Test fun uncertaintyOverlappingWallIsBoundary() { assertEquals(BuildingRelation.NEAR_BOUNDARY,locateBuilding(campus,Point(12.0,0.0),5.0).relation) }
    @Test fun clearOutsideIsOutside() { assertEquals(BuildingRelation.OUTSIDE,locateBuilding(campus,Point(20.0,0.0),5.0).relation) }
    @Test fun insideCannotSnapToRoadThroughWall() { assertNull(routeFromPosition(campus,Point(0.0,0.0),"y")) }
    @Test fun cannotConfirmOutsideWhileAccuracyOverlapsBuilding() { assertFalse(canLeaveBuilding(building,Point(12.0,0.0),5.0)) }
    @Test fun confirmsOutsideAfterClearingAccuracyMargin() { assertTrue(canLeaveBuilding(building,Point(20.0,0.0),5.0)) }
    @Test fun emptyFootprintDoesNotInventIndoorGeometry() { assertEquals(BuildingRelation.OUTSIDE,locateBuilding(campus.copy(buildings=listOf(building.copy(footprint=emptyList()))),Point(0.0,0.0),5.0).relation) }
}
