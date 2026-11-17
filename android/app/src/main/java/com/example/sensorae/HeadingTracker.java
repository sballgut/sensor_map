package com.example.sensorae;

import android.content.Context;
import android.hardware.*;
import android.os.SystemClock;
import android.view.Surface;

/** Magnetic north orientation. Fixed correction is configured, never obtained from GPS. */
public final class HeadingTracker implements SensorEventListener {
    public static final class HeadingState {
        public final Double degrees;
        public final int accuracy;
        public final long elapsedNs;
        public final String source;
        public final double fixedDeclination;
        HeadingState(Double degrees,int accuracy,long at,String source,double correction) {
            this.degrees=degrees;this.accuracy=accuracy;elapsedNs=at;this.source=source;fixedDeclination=correction;
        }
    }
    public interface Listener { void onHeading(HeadingState state); }
    private final SensorManager manager;
    private final Listener listener;
    private final java.util.function.IntSupplier displayRotation;
    private final float[] gravity=new float[3], magnetic=new float[3];
    private boolean hasGravity,hasMagnetic,rotationAvailable;
    private long gravityAt,magneticAt;
    private int accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE;
    private double correction;
    private String source="unsupported";
    public HeadingTracker(Context context, Listener listener, java.util.function.IntSupplier displayRotation) {
        manager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        this.listener=listener;this.displayRotation=displayRotation;
    }
    public void start(double fixedCorrection) {
        stop(); correction=fixedCorrection; accuracy=SensorManager.SENSOR_STATUS_UNRELIABLE;
        if(manager==null){emit(null,SystemClock.elapsedRealtimeNanos());return;}
        Sensor rotation=manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if(rotation!=null && manager.registerListener(this,rotation,SensorManager.SENSOR_DELAY_UI)) {
            rotationAvailable=true;source="rotation_vector";return;
        }
        Sensor acc=manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), mag=manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if(acc!=null && mag!=null) {
            boolean a=manager.registerListener(this,acc,SensorManager.SENSOR_DELAY_UI);
            boolean m=manager.registerListener(this,mag,SensorManager.SENSOR_DELAY_UI);
            if(a&&m){source="magnetometer_accelerometer";return;}
            manager.unregisterListener(this);
        }
        source="unsupported";emit(null,SystemClock.elapsedRealtimeNanos());
    }
    public void stop(){if(manager!=null)manager.unregisterListener(this);hasGravity=false;hasMagnetic=false;rotationAvailable=false;source="unsupported";}
    public void onAccuracyChanged(Sensor sensor,int value) {
        if(sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD || sensor.getType()==Sensor.TYPE_ROTATION_VECTOR) accuracy=value;
    }
    public void onSensorChanged(SensorEvent event) {
        float[] r=new float[9];
        if(rotationAvailable) {
            SensorManager.getRotationMatrixFromVector(r,event.values);accuracy=event.accuracy;
        } else {
            if(event.sensor.getType()==Sensor.TYPE_ACCELEROMETER){System.arraycopy(event.values,0,gravity,0,3);hasGravity=true;gravityAt=event.timestamp;}
            if(event.sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD){System.arraycopy(event.values,0,magnetic,0,3);hasMagnetic=true;magneticAt=event.timestamp;accuracy=event.accuracy;}
            if(!hasGravity||!hasMagnetic||Math.abs(gravityAt-magneticAt)>1_000_000_000L || !SensorManager.getRotationMatrix(r,null,gravity,magnetic))return;
        }
        int rotation=displayRotation.getAsInt(), x=SensorManager.AXIS_X,y=SensorManager.AXIS_Y;
        if(rotation==Surface.ROTATION_90){x=SensorManager.AXIS_Y;y=SensorManager.AXIS_MINUS_X;}
        else if(rotation==Surface.ROTATION_180){x=SensorManager.AXIS_MINUS_X;y=SensorManager.AXIS_MINUS_Y;}
        else if(rotation==Surface.ROTATION_270){x=SensorManager.AXIS_MINUS_Y;y=SensorManager.AXIS_X;}
        float[] remapped=new float[9],angles=new float[3];
        if(!SensorManager.remapCoordinateSystem(r,x,y,remapped))return;
        SensorManager.getOrientation(remapped,angles);
        // Nearly vertical phone: the screen-top horizontal direction becomes ambiguous.
        if(Math.abs(angles[1])>Math.toRadians(80)){emit(null,event.timestamp);return;}
        double degrees=(Math.toDegrees(angles[0])+correction+360)%360;
        emit(accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE ? null : degrees,event.timestamp);
    }
    private void emit(Double value,long at){listener.onHeading(new HeadingState(value,accuracy,at,source,correction));}
}
