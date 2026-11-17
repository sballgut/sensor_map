package com.example.sensorae;

import java.util.*;
import com.google.gson.Gson;

/** B/D integration surface. No positioning algorithm or walking-direction inference is fabricated. */
public final class TeamBridge {
    public interface Listener {
        void onObservationWindow(List<Observation> observations);
        void onHeading(HeadingTracker.HeadingState state);
    }
    public interface PositionSink { void onPosition(PositionEstimate value); }
    public interface ErrorListener { void onError(String operation, RuntimeException error); }
    public static final class PositionEstimate {
        public String session_id, status, model_version;
        public long utc_ms, elapsed_ns;
        public Double latitude,longitude,uncertainty_m;
    }
    private final List<Observation> window=new ArrayList<>();
    private final Gson gson=new Gson();
    private long lastEmission=-1;
    private Listener listener;
    private PositionSink sink;
    private ErrorListener errorListener;
    public synchronized void setListener(Listener value){listener=value;}
    public synchronized void setPositionSink(PositionSink value){sink=value;}
    public synchronized void setErrorListener(ErrorListener value){errorListener=value;}
    public void accept(Observation value){
        Listener target=null;
        List<Observation> observations=null;
        synchronized(this){
            window.add(copyObservation(value));
            long oldest=value.elapsed_ns-10_000_000_000L;
            window.removeIf(o->o.elapsed_ns<oldest);
            while(window.size()>3000)window.remove(0);
            if(listener!=null&&(lastEmission<0||value.elapsed_ns-lastEmission>=1_000_000_000L)){
                lastEmission=value.elapsed_ns;
                target=listener;
                observations=snapshot();
            }
        }
        // B/D may run inference, call back, or take a long time. Never hold our monitor here.
        if(target!=null){
            try{target.onObservationWindow(observations);}
            catch(RuntimeException error){reportError("observation_window",error);}
        }
    }
    public synchronized List<Observation> snapshot(){
        List<Observation> copy=new ArrayList<>();
        for(Observation o:window)copy.add(copyObservation(o));
        return Collections.unmodifiableList(copy);
    }
    public void heading(HeadingTracker.HeadingState value){
        Listener target;
        synchronized(this){target=listener;}
        if(target!=null){
            try{target.onHeading(value);}
            catch(RuntimeException error){reportError("heading",error);}
        }
    }
    public void submitPosition(PositionEstimate value){
        PositionSink target;
        synchronized(this){target=sink;}
        if(target!=null){
            // The sink can post to another thread; later caller mutation must not change its record.
            PositionEstimate copy=copyPosition(value);
            try{target.onPosition(copy);}
            catch(RuntimeException error){reportError("position",error);}
        }
    }
    public synchronized void clear(){window.clear();lastEmission=-1;}
    private Observation copyObservation(Observation value){
        return gson.fromJson(gson.toJson(Objects.requireNonNull(value,"observation")),Observation.class);
    }
    private static PositionEstimate copyPosition(PositionEstimate value){
        if(value==null)return null;
        PositionEstimate copy=new PositionEstimate();
        copy.session_id=value.session_id;copy.status=value.status;copy.model_version=value.model_version;
        copy.utc_ms=value.utc_ms;copy.elapsed_ns=value.elapsed_ns;
        copy.latitude=value.latitude;copy.longitude=value.longitude;copy.uncertainty_m=value.uncertainty_m;
        return copy;
    }
    private void reportError(String operation,RuntimeException error){
        ErrorListener target;
        synchronized(this){target=errorListener;}
        if(target!=null){
            try{target.onError(operation,error);return;}
            catch(RuntimeException diagnosticFailure){
                System.err.println("TeamBridge error listener failed");
                diagnosticFailure.printStackTrace(System.err);
            }
        }
        System.err.println("TeamBridge callback failed: "+operation);
        error.printStackTrace(System.err);
    }
}
