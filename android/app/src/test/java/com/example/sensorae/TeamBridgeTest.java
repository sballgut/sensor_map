package com.example.sensorae;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeamBridgeTest {
    private static Observation observation(String id,long elapsedNs){
        Observation value=new Observation();
        value.record_id=id;value.session_id="session";value.sensor_id="lab-1";
        value.elapsed_ns=elapsedNs;value.rssi=-50;
        return value;
    }
    private abstract static class Listener implements TeamBridge.Listener {
        @Override public void onHeading(HeadingTracker.HeadingState value){}
    }

    @Test public void observationAndSnapshotMutationsCannotChangeStoredWindow(){
        TeamBridge bridge=new TeamBridge();
        bridge.setListener(new Listener(){
            @Override public void onObservationWindow(List<Observation> values){values.get(0).rssi=10;}
        });
        Observation original=observation("one",0);
        bridge.accept(original);original.rssi=20;
        List<Observation> snapshot=bridge.snapshot();
        assertEquals(-50,snapshot.get(0).rssi);
        snapshot.get(0).rssi=30;
        assertEquals(-50,bridge.snapshot().get(0).rssi);
        try{snapshot.clear();fail("Snapshot list must be unmodifiable");}
        catch(UnsupportedOperationException expected){}
    }

    @Test public void windowUsesTenSecondsAndEmitsAtMostOncePerSecond(){
        TeamBridge bridge=new TeamBridge();
        AtomicInteger emissions=new AtomicInteger();
        bridge.setListener(new Listener(){
            @Override public void onObservationWindow(List<Observation> values){emissions.incrementAndGet();}
        });
        bridge.accept(observation("zero",0));
        bridge.accept(observation("half",500_000_000L));
        bridge.accept(observation("one",1_000_000_000L));
        assertEquals(2,emissions.get());
        bridge.accept(observation("ten",10_000_000_000L));
        assertEquals("zero",bridge.snapshot().get(0).record_id);
        bridge.accept(observation("eleven",11_000_000_000L));
        assertEquals(3,bridge.snapshot().size());
        assertEquals("one",bridge.snapshot().get(0).record_id);
    }

    @Test public void externalCallbacksNeverHoldBridgeMonitor(){
        TeamBridge bridge=new TeamBridge();
        AtomicInteger callbacks=new AtomicInteger();
        bridge.setListener(new Listener(){
            @Override public void onObservationWindow(List<Observation> values){assertFalse(Thread.holdsLock(bridge));callbacks.incrementAndGet();}
            @Override public void onHeading(HeadingTracker.HeadingState value){assertFalse(Thread.holdsLock(bridge));callbacks.incrementAndGet();}
        });
        bridge.setPositionSink(value->{assertFalse(Thread.holdsLock(bridge));callbacks.incrementAndGet();});
        bridge.accept(observation("one",0));bridge.heading(null);bridge.submitPosition(null);
        assertEquals(3,callbacks.get());
    }

    @Test public void callbackFailuresAreReportedAndLaterObservationsStillWork(){
        TeamBridge bridge=new TeamBridge();
        List<String> errors=new ArrayList<>();
        AtomicInteger attempts=new AtomicInteger();
        bridge.setErrorListener((operation,error)->{
            assertFalse(Thread.holdsLock(bridge));errors.add(operation);
        });
        bridge.setListener(new Listener(){
            @Override public void onObservationWindow(List<Observation> values){attempts.incrementAndGet();throw new IllegalStateException("inference failed");}
            @Override public void onHeading(HeadingTracker.HeadingState value){throw new IllegalArgumentException("heading failed");}
        });
        bridge.setPositionSink(value->{throw new IllegalStateException("sink failed");});
        bridge.accept(observation("one",0));bridge.accept(observation("two",1_000_000_000L));
        bridge.heading(null);bridge.submitPosition(null);
        assertEquals(2,attempts.get());assertEquals(2,bridge.snapshot().size());
        assertEquals(java.util.Arrays.asList("observation_window","observation_window","heading","position"),errors);
    }

    @Test public void positionSinkGetsIndependentSnapshot(){
        TeamBridge bridge=new TeamBridge();
        AtomicReference<TeamBridge.PositionEstimate> saved=new AtomicReference<>();
        bridge.setPositionSink(saved::set);
        TeamBridge.PositionEstimate original=new TeamBridge.PositionEstimate();
        original.session_id="first";original.latitude=36.5;original.uncertainty_m=2.0;
        bridge.submitPosition(original);
        original.session_id="changed";original.latitude=0.0;
        assertNotSame(original,saved.get());
        assertEquals("first",saved.get().session_id);assertEquals(36.5,saved.get().latitude,0);
    }

    @Test public void clearResetsEmissionForAnEarlierReplayTimeline(){
        TeamBridge bridge=new TeamBridge();
        AtomicInteger emissions=new AtomicInteger();
        bridge.setListener(new Listener(){
            @Override public void onObservationWindow(List<Observation> values){emissions.incrementAndGet();}
        });
        bridge.accept(observation("live",20_000_000_000L));bridge.clear();
        assertTrue(bridge.snapshot().isEmpty());
        bridge.accept(observation("replay",0));
        assertEquals(2,emissions.get());assertEquals("replay",bridge.snapshot().get(0).record_id);
    }
}
