package com.example.sensorae;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.view.WindowManager;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-owned collection state. Never retains an Activity or a View.
 * Hardware collection is started/stopped exclusively by CollectionService.
 * Main-thread hardware callbacks; ordered disk writes and independent network/inference workers.
 */
final class CollectionEngine {
    private static CollectionEngine instance;
    static synchronized CollectionEngine get(Context context) {
        if(instance==null)instance=new CollectionEngine(context.getApplicationContext());
        return instance;
    }
    private static final ParcelUuid ENV_UUID=ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    final Context context;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor();
    final ExecutorService network=Executors.newSingleThreadExecutor();
    final ExecutorService inference=Executors.newSingleThreadExecutor();
    final TeamBridge bridge=new TeamBridge();
    final ObservationStore store;
    final ReleaseInstaller releases;
    final SharedPreferences prefs;
    private final Gson gson=new Gson();
    private final AtomicBoolean syncing=new AtomicBoolean();
    private final HeadingTracker heading;
    private final LocationManager locations;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private LocationListener locationListener;
    private Location latestLocation;
    private HeadingTracker.HeadingState headingState;
    private PowerManager.WakeLock wakeLock;
    private boolean bluetoothReceiverRegistered;
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            int state=intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR);
            if(running&&(state==BluetoothAdapter.STATE_TURNING_OFF||state==BluetoothAdapter.STATE_OFF)){
                note("Bluetooth가 꺼져 수집을 중지했습니다. Bluetooth를 켠 뒤 다시 시작하세요.");stop();
            }
        }
    };
    private final String deviceId;
    String sessionId,activeMode="guidance",referencePoint="";
    boolean sessionConsent;
    double correction;
    volatile boolean running;
    private volatile long generation;
    private long received,lastReceivedNs,gpsRequests;
    private final Map<String,Integer> perSensor=new TreeMap<>();
    private String lastParse="";
    private volatile String syncStatus="서버 연결 미설정";
    private String diagnostics="";
    Runnable onStopped;

    private CollectionEngine(Context app) {
        context=app;
        prefs=app.getSharedPreferences("ae",Context.MODE_PRIVATE);
        String id=prefs.getString("device_id",null);
        if(id==null){id=UUID.randomUUID().toString();prefs.edit().putString("device_id",id).apply();}
        deviceId=id;
        store=new ObservationStore(app);releases=new ReleaseInstaller(app);
        locations=(LocationManager)app.getSystemService(Context.LOCATION_SERVICE);
        WindowManager windows=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        heading=new HeadingTracker(app,v->{headingState=v;long at=generation;
            inference.execute(()->{if(running&&at==generation)bridge.heading(v);});
        },()->windows.getDefaultDisplay().getRotation());
        bridge.setErrorListener((operation,error)->note("B/D 연결 오류 · "+operation+" · "+safe(error)));
        bridge.setPositionSink(value->main.post(()->recordPosition(value)));
    }

    void start(String requestedMode,boolean consent,String reference,double fixedCorrection) {
        if(running)return; // Repeated starts must not create a second scanner or session.
        if(!Arrays.asList("guidance","collection","evaluation").contains(requestedMode))throw new IllegalArgumentException("Unknown mode");
        if(!Double.isFinite(fixedCorrection)||Math.abs(fixedCorrection)>180)throw new IllegalArgumentException("Invalid correction");
        BluetoothManager bm=(BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=bm==null?null:bm.getAdapter();
        if(adapter==null||!adapter.isEnabled())throw new IllegalStateException("Bluetooth를 켜세요.");
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null)throw new IllegalStateException("BLE 스캐너를 사용할 수 없습니다.");
        activeMode=requestedMode;sessionConsent=consent;referencePoint=reference;correction=fixedCorrection;
        sessionId=UUID.randomUUID().toString();prefs.edit().putString("last_session",sessionId).apply();
        received=0;gpsRequests=0;lastReceivedNs=0;perSensor.clear();latestLocation=null;headingState=null;
        running=true;long current=++generation;
        inference.execute(()->{if(running&&current==generation)bridge.clear();});
        try {
            IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bluetoothState,filter,Context.RECEIVER_EXPORTED);
            else context.registerReceiver(bluetoothState,filter);
            bluetoothReceiverRegistered=true;
            PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SensorAE:collection");
            wakeLock.setReferenceCounted(false);wakeLock.acquire();
            if(activeMode.equals("guidance"))releases.pinForGuidance();else requestGps();
            heading.start(correction);
            startScan();
            main.postDelayed(refreshScan,240_000);
            main.post(uploadTick);
            note("백그라운드 수집 시작 · "+activeMode+" · "+sessionId);
        } catch(RuntimeException error) { stop();throw error; }
    }
    private void startScan() {
        long current=generation;
        scanCallback=new ScanCallback(){
            public void onScanResult(int type,ScanResult result){if(scanCallback==this)receive(current,result);}
            public void onBatchScanResults(List<ScanResult> results){if(scanCallback==this)for(ScanResult result:results)receive(current,result);}
            public void onScanFailed(int code){if(scanCallback!=this||current!=generation)return;note("BLE 수집 실패 코드 "+code+" · 앱에서 다시 시작하세요.");stop();}
        };
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceData(ENV_UUID,new byte[0]).build()),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),scanCallback);
    }
    private void stopScan() {
        ScanCallback previous=scanCallback;scanCallback=null;
        if(scanner!=null&&previous!=null)try{scanner.stopScan(previous);}catch(RuntimeException ignored){}
    }
    // Refresh before the long-running scan timeout; never restart on every missing packet.
    private final Runnable refreshScan=new Runnable(){public void run(){
        if(!running)return;
        stopScan();main.postDelayed(resumeScan,1000);
    }};
    private final Runnable resumeScan=new Runnable(){public void run(){
        if(!running)return;
        try{startScan();main.postDelayed(refreshScan,240_000);}
        catch(RuntimeException error){note("BLE 재시작 실패 · "+safe(error));stop();}
    }};
    private final Runnable uploadTick=new Runnable(){public void run(){
        if(!running)return;
        sync(prefs.getString("server_url",""),prefs.getString("server_token",""));
        main.postDelayed(this,30_000);
    }};

    private void requestGps(){
        if(activeMode.equals("guidance")||locations==null)return;
        locationListener=new LocationListener(){public void onLocationChanged(Location l){if(running&&!activeMode.equals("guidance"))latestLocation=new Location(l);}public void onProviderEnabled(String s){}public void onProviderDisabled(String s){}public void onStatusChanged(String s,int st,Bundle b){}};
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})try{if(locations.isProviderEnabled(provider)){gpsRequests++;locations.requestLocationUpdates(provider,2000,0,locationListener,Looper.getMainLooper());}}catch(SecurityException|IllegalArgumentException ex){note("GPS 라벨 요청 실패 · "+safe(ex));}
    }
    private void receive(long current,ScanResult result){
        if(!running||current!=generation||result==null)return;
        ScanRecord record=result.getScanRecord();if(record==null)return;
        byte[] raw=record.getServiceData(ENV_UUID);if(raw==null)return;
        Observation o=new Observation();o.record_id=UUID.randomUUID().toString();o.session_id=sessionId;o.device_id=deviceId;
        o.utc_ms=System.currentTimeMillis();o.elapsed_ns=SystemClock.elapsedRealtimeNanos();o.mode=activeMode;o.candidate_consent=sessionConsent;
        o.reference_point_id=referencePoint;o.advertised_name=record.getDeviceName();
        try{o.mac=result.getDevice().getAddress();}catch(SecurityException ex){o.mac="";}
        o.rssi=result.getRssi();PacketParser.parse(o.advertised_name,raw).applyTo(o);
        if(!activeMode.equals("guidance")&&latestLocation!=null){long age=(SystemClock.elapsedRealtimeNanos()-latestLocation.getElapsedRealtimeNanos())/1_000_000;
            if(age>=0&&age<=30_000&&latestLocation.hasAccuracy()&&latestLocation.getAccuracy()<=100){o.gps_lat=latestLocation.getLatitude();o.gps_lon=latestLocation.getLongitude();o.gps_accuracy=(double)latestLocation.getAccuracy();o.gps_age_ms=age;}}
        if(headingState!=null && o.elapsed_ns-headingState.elapsedNs<=2_000_000_000L){o.heading_deg=headingState.degrees;o.heading_accuracy=headingState.accuracy;o.heading_source=headingState.source;o.fixed_declination_deg=headingState.fixedDeclination;}
        received++;lastReceivedNs=o.elapsed_ns;lastParse=o.parse_status;
        String id=o.sensor_id==null?"미식별 (RSSI 보존)":o.sensor_id;perSensor.put(id,perSensor.getOrDefault(id,0)+1);
        io.execute(()->{try{store.insert(o);}catch(Exception e){note("관측 저장 실패 · "+safe(e));}});inference.execute(()->{if(current==generation)bridge.accept(o);});
    }

    void stop() {
        boolean wasRunning=running;
        running=false;generation++;
        main.removeCallbacks(refreshScan);main.removeCallbacks(resumeScan);main.removeCallbacks(uploadTick);
        stopScan();
        if(bluetoothReceiverRegistered){context.unregisterReceiver(bluetoothState);bluetoothReceiverRegistered=false;}
        if(locations!=null&&locationListener!=null)try{locations.removeUpdates(locationListener);}catch(SecurityException ignored){}
        locationListener=null;latestLocation=null;heading.stop();releases.unpin();
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();wakeLock=null;
        if(wasRunning){note("수집 중지 · 저장된 관측과 미전송 대기열은 유지됩니다.");if(onStopped!=null)onStopped.run();}
    }
    void sync(String base,String key) {
        if(base.isEmpty()||key.isEmpty()){syncStatus="E 서버 주소와 APP_TOKEN 설정 필요";return;}
        if(Build.VERSION.SDK_INT>=37&&context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED){syncStatus="로컬 네트워크 권한 필요 · 앱에서 배치 전송 버튼을 누르세요";return;}
        if(!syncing.compareAndSet(false,true))return;
        network.execute(()->{try{
            int ack=0;BatchUploader.Result result=null;
            for(int i=0;i<10;i++){result=new BatchUploader(base,key,store).uploadOnce();ack+=result.acknowledged;if(result.pending==0||result.acknowledged==0)break;}
            if(result!=null)syncStatus="전송 확인 "+ack+"건 · 대기 "+result.pending+"건 · "+result.message;
        }catch(Exception error){syncStatus="전송 실패 · 대기열 유지";note(syncStatus+" · "+safe(error));}finally{syncing.set(false);}});
    }
    String status() {
        StringBuilder s=new StringBuilder("모드: "+activeMode+" · "+(running?"백그라운드 수집 중":"중지")+"\n세션: "+(sessionId==null?"없음":sessionId)+"\n관측 "+received+"건 · GPS 요청 "+gpsRequests+"회 · "+lastParse);
        if(running&&lastReceivedNs>0)s.append("\n마지막 BLE 수신 ").append((SystemClock.elapsedRealtimeNanos()-lastReceivedNs)/1_000_000_000L).append("초 전");
        if(headingState!=null)s.append("\n휴대폰 방향: ").append(headingState.degrees==null?"불확실/미지원":String.format(Locale.US,"%.1f°",headingState.degrees)).append(" · 정확도 ").append(headingState.accuracy).append(" · ").append(headingState.source);
        if(latestLocation!=null&&!activeMode.equals("guidance"))s.append("\nGPS ").append(latestLocation.getLatitude()).append(", ").append(latestLocation.getLongitude());
        s.append("\n").append(syncStatus).append("\n배포 버전: ").append(releases.currentVersion());
        for(Map.Entry<String,Integer> e:perSensor.entrySet())s.append("\n").append(e.getKey()).append(" · ").append(e.getValue()).append("건");
        return s.toString();
    }
    synchronized void note(String text){diagnostics=text+"\n\n"+diagnostics;diagnostics=diagnostics.substring(0,Math.min(8000,diagnostics.length()));}
    synchronized String diagnostics(){return diagnostics;}
    private static String safe(Exception e){return e.getClass().getSimpleName()+": "+(e.getMessage()==null?"":e.getMessage());}
    private void recordPosition(TeamBridge.PositionEstimate p){
        if(!running||!sessionConsent||p==null||!sessionId.equals(p.session_id))return;
        if(!validPosition(p)){note("B 추정값 제외 · 좌표/불확실성/시각 형식을 확인하세요.");return;}
        final String json;try{json=gson.toJson(p);}catch(RuntimeException ex){note("B 추정값 제외 · "+safe(ex));return;}io.execute(()->{try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"candidate_positions.jsonl"),true)){out.write((json+"\n").getBytes(StandardCharsets.UTF_8));}catch(Exception e){note("후보 위치 기록 실패 · "+safe(e));}});
    }
    private static boolean validPosition(TeamBridge.PositionEstimate p){
        return p.utc_ms>=0&&p.elapsed_ns>=0
            &&(p.latitude==null||(Double.isFinite(p.latitude)&&Math.abs(p.latitude)<=90))
            &&(p.longitude==null||(Double.isFinite(p.longitude)&&Math.abs(p.longitude)<=180))
            &&((p.latitude==null)==(p.longitude==null))
            &&(p.uncertainty_m==null||(Double.isFinite(p.uncertainty_m)&&p.uncertainty_m>=0));
    }
}
