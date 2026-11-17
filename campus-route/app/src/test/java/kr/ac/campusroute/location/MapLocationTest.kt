package kr.ac.campusroute.location

import org.junit.Assert.*
import org.junit.Test
import kr.ac.campusroute.domain.model.Point

class MapLocationTest {
    private fun fix(accuracy: Double? = 8.0, approximate: Boolean = false) = MapFix(36.6285,127.4565,accuracy,1700000000000,10000,approximate=approximate)
    @Test fun freshFixAllowsFollowing() { assertEquals(LocationCondition.VALID,classifyFix(fix(),11000).condition) }
    @Test fun staleFixStopsFollowingAndPreservesLastMeasurement() {
        val state=classifyFix(fix(),40000)
        assertEquals(LocationCondition.STALE,state.condition); assertFalse(state.canFollow); assertEquals(30L,state.ageSeconds); assertNotNull(state.fix)
    }
    @Test fun approximateFixIsUncertainEvenIfProviderClaimsSmallRadius() { assertEquals(LocationCondition.UNCERTAIN,classifyFix(fix(8.0,true),11000).condition) }
    @Test fun poorAccuracyStopsFollowing() { assertFalse(classifyFix(fix(150.0),11000).canFollow) }
    @Test fun absentAccuracyIsNotInvented() { val state=classifyFix(fix(null),11000); assertEquals(LocationCondition.UNCERTAIN,state.condition); assertNull(state.fix!!.accuracyMetres) }
    @Test fun futureMonotonicTimeIsNotLive() { assertEquals(LocationCondition.STALE,classifyFix(fix(),9000).condition) }
    @Test fun originProjectsToZero() { assertEquals(Point(0.0,0.0),projectWgs84(36.6285,127.4565,36.6285,127.4565)) }
    @Test fun projectionMatchesImporterMetres() {
        val p=projectWgs84(36.6295,127.4575,36.6285,127.4565)
        assertEquals(111.195,p.y,.002); assertEquals(89.24,p.x,.1)
    }
    @Test fun outsideCampusIsNotClampedToCampus() {
        val ring=listOf(Point(-10.0,-10.0),Point(10.0,-10.0),Point(10.0,10.0),Point(-10.0,10.0))
        assertTrue(containsPoint(Point(0.0,0.0),ring)); assertFalse(containsPoint(Point(500.0,0.0),ring))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsInvalidCoordinate() { MapFix(999.0,127.0,1.0,0,0) }
}
