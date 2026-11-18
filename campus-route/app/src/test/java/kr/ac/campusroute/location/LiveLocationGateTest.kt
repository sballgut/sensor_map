package kr.ac.campusroute.location

import org.junit.Assert.*
import org.junit.Test

class LiveLocationGateTest {
    private fun fix(north: Double=0.0,time: Long=1000,accuracy: Double=5.0)=MapFix(36.6285+north/111195,127.4565,accuracy,time,time)
    private fun state(f: MapFix)=MapLocationState(LocationCondition.VALID,f,0)
    @Test fun noNewMeasurementDoesNotAdvancePosition() {
        val gate=LiveLocationGate(36.6285,127.4565); val input=state(fix())
        repeat(100) { assertEquals(input,gate.update(input)) }
    }
    @Test fun acceptedWalkingMovesExactlyToMeasurement() {
        val gate=LiveLocationGate(36.6285,127.4565);gate.update(state(fix()))
        val next=fix(1.4,2000);assertEquals(next,gate.update(state(next)).fix)
    }
    @Test fun rejectedJumpFreezesDisplayedPositionToo() {
        val gate=LiveLocationGate(36.6285,127.4565);val first=fix();gate.update(state(first))
        val result=gate.update(state(fix(100.0,2000)))
        assertEquals(first,result.fix);assertFalse(result.canFollow)
        assertEquals(result,gate.update(state(fix(100.0,2000))))
    }
    @Test fun poorAccuracyFreezesMarkerAndProgress() {
        val gate=LiveLocationGate(36.6285,127.4565);val first=fix();gate.update(state(first))
        val result=gate.update(state(fix(15.0,2000,80.0)))
        assertEquals(first,result.fix);assertFalse(result.canFollow)
    }
    @Test fun staleOrDisabledDoesNotMove() {
        val gate=LiveLocationGate(36.6285,127.4565);val first=fix();gate.update(state(first))
        for(status in listOf(LocationCondition.STALE,LocationCondition.DISABLED,LocationCondition.PAUSED)) {
            val result=gate.update(MapLocationState(status,fix(100.0,2000)))
            assertEquals(first,result.fix);assertFalse(result.canFollow)
        }
    }
    @Test fun permissionRevocationRemovesPosition() {
        val gate=LiveLocationGate(36.6285,127.4565);gate.update(state(fix()))
        assertNull(gate.update(MapLocationState(LocationCondition.PERMISSION_REQUIRED)).fix)
    }
    @Test fun outOfOrderCannotMoveBackwards() {
        val gate=LiveLocationGate(36.6285,127.4565);val latest=state(fix(2.0,2000));gate.update(latest)
        assertEquals(latest,gate.update(state(fix(0.0,1000))))
    }
    @Test fun goodFixRecoversAfterJump() {
        val gate=LiveLocationGate(36.6285,127.4565);gate.update(state(fix()))
        gate.update(state(fix(100.0,2000)))
        val valid=state(fix(2.0,3000));assertEquals(valid,gate.update(valid))
    }
}
