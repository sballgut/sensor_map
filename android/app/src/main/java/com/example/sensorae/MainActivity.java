package com.example.sensorae;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.google.gson.*;
import com.example.sensorae.ui.d1.DestinationHomeView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Foreground research shell: real BLE observations, public map snapshots, and owned-server sync. */
public final class MainActivity extends Activity {
    private static final ParcelUuid ENV_UUID=ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ExecutorService inference=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean syncing=new java.util.concurrent.atomic.AtomicBoolean();
    public final TeamBridge bridge=new TeamBridge();
    private final Gson gson=new Gson();
    private ObservationStore store;
    private PublicApi publicApi;
    private ReleaseInstaller releases;
    private HeadingTracker heading;
    private HeadingTracker.HeadingState headingState;
    private SharedPreferences prefs;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private LocationManager locations;
    private LocationListener locationListener;
    private Location latestLocation;
    private Spinner mode;
    private CheckBox consent;
    private EditText url,token,declination,reference,description;
    private TextView summary,logView,mapView;
    private ViewFlipper screens;
    private DestinationHomeView destinationHome;
    private String sessionId,deviceId,activeMode="guidance";
    private volatile boolean running,destroyed;
    private boolean sessionConsent,replaying;
    private volatile long generation;
    private long received,lastReceivedNs,gpsRequests;
    private int exportKind;
    private final Map<String,Integer> perSensor=new TreeMap<>();
    private final Map<String,Long> lastSeen=new HashMap<>();
    private String lastParse="",syncStatus="서버 연결 미설정";
    private final Runnable uploadTick=new Runnable(){public void run(){
        if(destroyed)return;
        if(!url.getText().toString().trim().isEmpty()&&!token.getText().toString().trim().isEmpty())sync();
        main.postDelayed(this,30_000);
    }};
    private final Runnable statusTick=new Runnable(){public void run(){
        if(destroyed)return;
        if(replaying){main.postDelayed(this,2000);return;}
        StringBuilder s=new StringBuilder("모드: "+activeMode+" · "+(running?"수집 중":"중지")+"\n세션: "+(sessionId==null?"없음":sessionId)+"\n관측 "+received+"건 · GPS 요청 "+gpsRequests+"회 · "+lastParse);
        if(running&&lastReceivedNs>0)s.append("\n마지막 BLE 수신 ").append((SystemClock.elapsedRealtimeNanos()-lastReceivedNs)/1_000_000_000L).append("초 전");
        if(headingState!=null)s.append("\n휴대폰 방향: ").append(headingState.degrees==null?"불확실/미지원":String.format(Locale.US,"%.1f°",headingState.degrees)).append(" · 정확도 ").append(headingState.accuracy).append(" · ").append(headingState.source);
        if(latestLocation!=null&&!activeMode.equals("guidance"))s.append("\nGPS ").append(latestLocation.getLatitude()).append(", ").append(latestLocation.getLongitude());
        s.append("\n").append(syncStatus).append("\n배포 버전: ").append(releases.currentVersion());
        for(Map.Entry<String,Integer> e:perSensor.entrySet())s.append("\n").append(e.getKey()).append(" · ").append(e.getValue()).append("건");
        summary.setText(s.toString());main.postDelayed(this,2000);
    }};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("ae",MODE_PRIVATE);
        deviceId=prefs.getString("device_id",null);
        if(deviceId==null){deviceId=UUID.randomUUID().toString();prefs.edit().putString("device_id",deviceId).apply();}
        store=new ObservationStore(this);publicApi=new PublicApi(this);releases=new ReleaseInstaller(this);
        locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        heading=new HeadingTracker(this,v->{headingState=v;long at=generation;if(!destroyed)inference.execute(()->{if(!destroyed&&running&&at==generation)bridge.heading(v);});},()->getWindowManager().getDefaultDisplay().getRotation());
        buildUi();
        destinationHome.restoreSelection(state);
        if(state!=null)screens.setDisplayedChild(state.getBoolean("d1.collection",false)?1:0);
        bridge.setErrorListener((operation,error)->note("B/D 연결 오류 · "+operation+" · "+safe(error)));
        bridge.setPositionSink(value->{main.post(()->recordPosition(value));});
        io.execute(()->{try{List<PublicApi.SensorSnapshot> cached=publicApi.cachedMap();main.post(()->renderMap(cached,"캐시"));}catch(Exception ex){note("캐시 없음 · 센서 목록 새로고침을 누르세요.");}});
        main.post(statusTick);main.postDelayed(uploadTick,30_000);
    }
    private void buildUi(){
        screens=new ViewFlipper(this);
        destinationHome=new DestinationHomeView(this,()->{((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(screens.getWindowToken(),0);screens.setDisplayedChild(1);});
        screens.addView(destinationHome,new ViewGroup.LayoutParams(-1,-1));
        ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);int padding=(int)(16*getResources().getDisplayMetrics().density);box.setPadding(padding,padding,padding,padding);scroll.addView(box);
        screens.addView(scroll,new ViewGroup.LayoutParams(-1,-1));setContentView(screens);
        if(Build.VERSION.SDK_INT>=30){screens.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});screens.requestApplyInsets();}
        button(box,"← 목적지 검색으로",()->{((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(screens.getWindowToken(),0);screens.setDisplayedChild(0);});
        TextView title=new TextView(this);title.setText("Sensor A·E · 수집 실험실");title.setTextSize(25);box.addView(title);
        TextView intro=new TextView(this);intro.setText("34개 센서 레지스트리 / 주변 BLE 관측 / 팀 서버 전송\n안내는 GPS 미사용 모드입니다. 실제 길 안내 UI는 B·C·D 연동 후 추가합니다.");box.addView(intro);
        mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"안내 · GPS 미사용","수집 · GPS 라벨","평가 · GPS 라벨"}));box.addView(mode);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(running)stop();latestLocation=null;activeMode=new String[]{"guidance","collection","evaluation"}[pos];}});
        reference=field(box,"기준 지점 ID (선택)","");
        declination=field(box,"고정 자북 보정각(도) · 미확인 시 0",prefs.getString("declination","0"));
        consent=new CheckBox(this);consent.setText("이 세션을 경로 후보 근거로 기록하는 데 동의");box.addView(consent);
        consent.setOnCheckedChangeListener((b,checked)->{if(checked)new AlertDialog.Builder(this).setTitle("후보 기록 동의").setMessage("BLE 관측, B가 제공한 추정 위치·불확실성을 세션에 연결해 저장합니다. 서버 제출은 별도 버튼입니다. 동의를 끄면 이후 세션의 후보 기록을 만들지 않습니다. 일반 실험 관측은 동의 여부와 관계없이 로컬 저장됩니다.").setPositiveButton("동의",(d,w)->{}).setNegativeButton("취소",(d,w)->consent.setChecked(false)).setOnCancelListener(d->consent.setChecked(false)).show();});
        button(box,"BLE 수집 시작",()->startRequested());button(box,"수집 중지",()->stop());
        summary=new TextView(this);summary.setTextSize(14);box.addView(summary);
        button(box,"공용 API · 전체 센서 최신값 새로고침",()->network.execute(()->{try{List<PublicApi.SensorSnapshot> data=publicApi.refreshMap();main.post(()->renderMap(data,"서버 조회"));}catch(Exception e){note("공용 API 실패 · 캐시 유지 · "+safe(e));}}));
        mapView=new TextView(this);box.addView(mapView);
        url=field(box,"E 서버 주소 (예: http://192.168.0.10:8080)",prefs.getString("server_url",""));
        token=field(box,"E 서버 APP_TOKEN",prefs.getString("server_token",""));token.setInputType(129);
        button(box,"서버 설정 저장",()->{prefs.edit().putString("server_url",url.getText().toString().trim()).putString("server_token",token.getText().toString().trim()).putString("declination",declination.getText().toString()).apply();note("서버 설정 저장 완료");});
        button(box,"대기 관측 배치 전송",()->{if(serverPermission(true))sync();});
        button(box,"관측 CSV 내보내기",()->export(1));
        button(box,"후보 추정 JSONL 내보내기",()->export(2));
        button(box,"마지막 세션 재생",()->replay());
        description=field(box,"후보 설명 (B의 추정이 없으면 관측 세션 근거만 제출)","");
        button(box,"동의한 세션 후보 제출",()->submitCandidate());
        button(box,"지도·그래프·모델 업데이트",()->{if(!serverPermission(true))return;String base=url.getText().toString().trim();network.execute(()->{try{note("업데이트 완료 · "+releases.install(base));}catch(Exception e){note("업데이트 실패 · 기존 버전 유지 · "+safe(e));}});});
        button(box,"이전 패키지로 복구",()->network.execute(()->{try{note("이전 버전 복구 · "+releases.rollback());}catch(Exception e){note("복구 실패 · "+safe(e));}}));
        logView=new TextView(this);box.addView(logView);
    }
    private EditText field(LinearLayout box,String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine();e.setText(value);box.addView(e);return e;}
    private void button(LinearLayout box,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());box.addView(b);}
    private void startRequested(){
        stop();
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);}
        // BLE observations are used for physical location inference, so neverForLocation
        // is not appropriate. Permission does not enable GPS providers in guidance mode.
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){missing.add(Manifest.permission.ACCESS_FINE_LOCATION);missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);}
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),100);return;}
        start();
    }
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] g){super.onRequestPermissionsResult(c,p,g);if(c==100){boolean allowed=g.length==p.length&&g.length>0;for(int i=0;i<Math.min(p.length,g.length);i++){if(!p[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)&&g[i]!=PackageManager.PERMISSION_GRANTED)allowed=false;}if(allowed)start();else note("주변 기기와 정확한 위치 권한을 확인하고 다시 시작하세요. BLE 위치 추정에 권한이 필요하지만 안내 모드는 GPS 좌표를 요청하지 않습니다.");}}
    @SuppressWarnings("MissingPermission") private void start(){
        double correction;try{correction=Double.parseDouble(declination.getText().toString());if(!Double.isFinite(correction)||Math.abs(correction)>180)throw new Exception();}catch(Exception e){note("보정각을 -180~180 범위로 입력하세요.");return;}
        BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter a=bm==null?null:bm.getAdapter();
        if(a==null||!a.isEnabled()){note("Bluetooth를 켜세요.");return;}
        scanner=a.getBluetoothLeScanner();if(scanner==null){note("BLE 스캐너를 사용할 수 없습니다.");return;}
        sessionId=UUID.randomUUID().toString();prefs.edit().putString("last_session",sessionId).apply();
        sessionConsent=consent.isChecked();received=0;gpsRequests=0;lastReceivedNs=0;perSensor.clear();latestLocation=null;headingState=null;
        running=true;long current=++generation;inference.execute(()->{if(!destroyed&&current==generation)bridge.clear();});consent.setEnabled(false);declination.setEnabled(false);reference.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(activeMode.equals("guidance"))releases.pinForGuidance();else requestGps();
        heading.start(correction);
        scanCallback=new ScanCallback(){public void onScanResult(int type,ScanResult result){receive(current,result);}public void onScanFailed(int code){if(current!=generation)return;note("BLE 시작/수집 실패 코드 "+code+" · 다시 시작하세요.");stop();}};
        try{scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceData(ENV_UUID,new byte[0]).build()),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);note("수집 시작 · "+activeMode+" · "+sessionId);}catch(SecurityException|IllegalStateException ex){stop();note("BLE 오류 · "+safe(ex));}
    }
    private void requestGps(){
        if(activeMode.equals("guidance")||locations==null)return;
        locationListener=new LocationListener(){public void onLocationChanged(Location l){if(running&&!activeMode.equals("guidance"))latestLocation=new Location(l);}public void onProviderEnabled(String s){}public void onProviderDisabled(String s){}public void onStatusChanged(String s,int st,Bundle b){}};
        for(String provider:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER})try{if(locations.isProviderEnabled(provider)){gpsRequests++;locations.requestLocationUpdates(provider,2000,0,locationListener,Looper.getMainLooper());}}catch(SecurityException|IllegalArgumentException ex){note("GPS 라벨 요청 실패 · "+safe(ex));}
    }
    private void receive(long current,ScanResult result){
        if(!running||destroyed||current!=generation||result==null)return;
        ScanRecord record=result.getScanRecord();if(record==null)return;
        byte[] raw=record.getServiceData(ENV_UUID);if(raw==null)return;
        Observation o=new Observation();o.record_id=UUID.randomUUID().toString();o.session_id=sessionId;o.device_id=deviceId;
        o.utc_ms=System.currentTimeMillis();o.elapsed_ns=SystemClock.elapsedRealtimeNanos();o.mode=activeMode;o.candidate_consent=sessionConsent;
        o.reference_point_id=reference.getText().toString().trim();o.advertised_name=record.getDeviceName();
        try{o.mac=result.getDevice().getAddress();}catch(SecurityException ex){o.mac="";}
        o.rssi=result.getRssi();PacketParser.parse(o.advertised_name,raw).applyTo(o);
        if(!activeMode.equals("guidance")&&latestLocation!=null){long age=(SystemClock.elapsedRealtimeNanos()-latestLocation.getElapsedRealtimeNanos())/1_000_000;
            if(age>=0&&age<=30_000&&latestLocation.hasAccuracy()&&latestLocation.getAccuracy()<=100){o.gps_lat=latestLocation.getLatitude();o.gps_lon=latestLocation.getLongitude();o.gps_accuracy=(double)latestLocation.getAccuracy();o.gps_age_ms=age;}}
        if(headingState!=null && o.elapsed_ns-headingState.elapsedNs<=2_000_000_000L){o.heading_deg=headingState.degrees;o.heading_accuracy=headingState.accuracy;o.heading_source=headingState.source;o.fixed_declination_deg=headingState.fixedDeclination;}
        received++;lastReceivedNs=o.elapsed_ns;lastParse=o.parse_status;
        String id=o.sensor_id==null?"미식별 (RSSI 보존)":o.sensor_id;perSensor.put(id,perSensor.getOrDefault(id,0)+1);lastSeen.put(id,o.utc_ms);
        io.execute(()->{try{store.insert(o);}catch(Exception e){note("관측 저장 실패 · "+safe(e));}});inference.execute(()->{if(!destroyed&&current==generation)bridge.accept(o);});
    }
    private void stop(){
        running=false;replaying=false;generation++;
        if(scanner!=null&&scanCallback!=null)try{scanner.stopScan(scanCallback);}catch(Exception ignored){}
        scanCallback=null;
        if(locations!=null&&locationListener!=null)try{locations.removeUpdates(locationListener);}catch(SecurityException ignored){}
        locationListener=null;latestLocation=null;if(heading!=null)heading.stop();if(releases!=null)releases.unpin();
        if(consent!=null){consent.setEnabled(true);declination.setEnabled(true);reference.setEnabled(true);}
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void sync(){
        String base=url.getText().toString().trim(),key=token.getText().toString().trim();
        if(base.isEmpty()||key.isEmpty()){note("E 서버 주소와 APP_TOKEN을 입력하세요. 공용 조회 API는 ACK 배치 서버가 아닙니다.");return;}
        if(!serverPermission(false)){syncStatus="로컬 네트워크 권한 필요 · 배치 전송 버튼을 누르세요";return;}
        if(!syncing.compareAndSet(false,true))return;
        network.execute(()->{try{
            int ack=0;BatchUploader.Result r=null;
            for(int i=0;i<10&&!destroyed;i++){r=new BatchUploader(base,key,store).uploadOnce();ack+=r.acknowledged;if(r.pending==0||r.acknowledged==0)break;}
            if(r!=null){final String text="전송 확인 "+ack+"건 · 대기 "+r.pending+"건 · "+r.message;main.post(()->syncStatus=text);}
        }catch(Exception e){note("전송 실패 · 대기열 유지 · "+safe(e));}finally{syncing.set(false);}});
    }
    private void renderMap(List<PublicApi.SensorSnapshot> snapshots,String origin){
        Map<String,PublicApi.SensorSnapshot> all=new HashMap<>();for(PublicApi.SensorSnapshot s:snapshots)all.put(s.sensor_id,s);
        StringBuilder text=new StringBuilder("공용 서버 최신값 · "+origin+" · "+all.size()+"개\n설치 좌표는 휴대폰 GPS와 별개입니다. API에 없는 센서는 미제공으로 표시합니다.\n");
        for(int i=1;i<=34;i++){String id=String.format(Locale.US,"sensor %02d",i);PublicApi.SensorSnapshot s=all.get(id);text.append(id).append(" · ");if(s==null)text.append("API 미제공");else text.append(s.temperature).append("°C / CO₂ ").append(s.co2).append(" · ").append(s.fresh?"최근 1시간":"오래된 값").append("\n  설치 ").append(s.latitude).append(", ").append(s.longitude).append(" · ").append(s.mode).append(" · ").append(s.time);text.append('\n');}
        mapView.setText(text.toString());
    }
    private void replay(){String id=prefs.getString("last_session",null);if(id==null){note("저장된 세션이 없습니다.");return;}io.execute(()->{try{List<Observation> rows=store.session(id);main.post(()->{if(running){note("수집을 중지한 뒤 재생하세요.");return;}long current=++generation;inference.execute(()->{if(!destroyed&&current==generation)bridge.clear();});replaying=true;note("재생 시작 · "+rows.size()+"건 · 실제 전송/재저장 없음");replayRows(rows,0,current);});}catch(Exception e){note("재생 오류 · "+safe(e));}});}
    private void replayRows(List<Observation> rows,int i,long current){if(destroyed||current!=generation||running)return;if(i>=rows.size()){replaying=false;note("재생 완료");return;}Observation o=rows.get(i);inference.execute(()->{if(!destroyed&&current==generation)bridge.accept(o);});summary.setText("재생 "+(i+1)+"/"+rows.size()+"\n"+o.sensor_id+" RSSI "+o.rssi+" · "+o.parse_status);long delay=i+1<rows.size()?Math.min(2000,Math.max(10,(rows.get(i+1).elapsed_ns-o.elapsed_ns)/1_000_000)):10;main.postDelayed(()->replayRows(rows,i+1,current),delay);}
    private void recordPosition(TeamBridge.PositionEstimate p){
        if(!running||!sessionConsent||p==null||!sessionId.equals(p.session_id))return;
        if(!validPosition(p)){note("B 추정값 제외 · 좌표/불확실성/시각 형식을 확인하세요.");return;}
        final String json;try{json=gson.toJson(p);}catch(RuntimeException ex){note("B 추정값 제외 · "+safe(ex));return;}io.execute(()->{try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"candidate_positions.jsonl"),true)){out.write((json+"\n").getBytes(StandardCharsets.UTF_8));}catch(Exception e){note("후보 위치 기록 실패 · "+safe(e));}});
    }
    private static boolean validPosition(TeamBridge.PositionEstimate p){
        return p.utc_ms>=0&&p.elapsed_ns>=0
            &&(p.latitude==null||(Double.isFinite(p.latitude)&&Math.abs(p.latitude)<=90))
            &&(p.longitude==null||(Double.isFinite(p.longitude)&&Math.abs(p.longitude)<=180))
            &&((p.latitude==null)==(p.longitude==null))
            &&(p.uncertainty_m==null||(Double.isFinite(p.uncertainty_m)&&p.uncertainty_m>=0));
    }
    private void submitCandidate(){
        if(sessionId==null||!sessionConsent){note("후보 기록에 동의하고 수집한 세션이 필요합니다.");return;}
        final String sid=sessionId,base=url.getText().toString().trim(),key=token.getText().toString().trim(),desc=description.getText().toString().trim();
        if(base.isEmpty()||key.isEmpty()||desc.isEmpty()){note("서버 설정과 후보 설명을 입력하세요.");return;}
        if(!serverPermission(true))return;
        network.execute(()->{try{List<Observation> observations=store.session(sid);JsonArray ids=new JsonArray();for(int i=0;i<Math.min(5000,observations.size());i++)if(observations.get(i).candidate_consent)ids.add(observations.get(i).record_id);if(ids.size()==0)throw new IOException("동의한 관측 없음");
            JsonObject body=new JsonObject();body.addProperty("consent",true);body.addProperty("session_id",sid);body.addProperty("description",desc);body.add("evidence_record_ids",ids);
            JsonArray estimates=new JsonArray();File positionFile=new File(getFilesDir(),"candidate_positions.jsonl");
            if(positionFile.exists())try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(positionFile),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null&&estimates.size()<5000){try{JsonObject obj=JsonParser.parseString(line).getAsJsonObject();if(obj.has("session_id")&&sid.equals(obj.get("session_id").getAsString()))estimates.add(obj);}catch(RuntimeException ignored){}}}
            body.add("position_estimates",estimates);
            URL endpoint=new URL(base.replaceAll("/+$","")+"/v1/candidates");if(!endpoint.getProtocol().matches("https?"))throw new IOException("HTTP(S) 주소 필요");
            HttpURLConnection c=(HttpURLConnection)endpoint.openConnection();try{c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));}int status=c.getResponseCode();try(InputStream in=status<400?c.getInputStream():c.getErrorStream()){String response=in==null?"":PublicApi.readLimited(in,2*1024*1024);note("후보 제출 HTTP "+status+" · "+response);}}finally{c.disconnect();}
        }catch(Exception e){note("후보 제출 실패 · 먼저 관측 배치를 전송하고 다시 시도하세요 · "+safe(e));}});
    }
    private boolean serverPermission(boolean request){
        if(Build.VERSION.SDK_INT<37||checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED)return true;
        if(request){note("같은 Wi-Fi의 E 서버 연결을 허용한 뒤 해당 버튼을 다시 누르세요.");requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},101);}
        return false;
    }
    private void export(int kind){exportKind=kind;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(kind==1?"text/csv":"application/octet-stream").putExtra(Intent.EXTRA_TITLE,kind==1?"observations.csv":"candidate_positions.jsonl"),200);}
    @Override protected void onActivityResult(int r,int result,Intent data){super.onActivityResult(r,result,data);if(r==200&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();int kind=exportKind;io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("저장 위치 열기 실패");if(kind==1)store.exportCsv(out);else{File f=new File(getFilesDir(),"candidate_positions.jsonl");if(f.exists())try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}note("내보내기 완료");}catch(Exception e){note("내보내기 실패 · "+safe(e));}});}}
    private String safe(Exception e){return e.getClass().getSimpleName()+": "+(e.getMessage()==null?"":e.getMessage());}
    private void note(String text){main.post(()->{if(destroyed||logView==null)return;String old=logView.getText().toString();String msg=text.length()>2000?text.substring(0,2000):text;logView.setText(msg+"\n\n"+old.substring(0,Math.min(6000,old.length())));});}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(destinationHome!=null)destinationHome.saveSelection(state);if(screens!=null)state.putBoolean("d1.collection",screens.getDisplayedChild()==1);}
    @Override protected void onStop(){stop();super.onStop();}
    @Override protected void onDestroy(){stop();destroyed=true;main.removeCallbacksAndMessages(null);network.shutdown();inference.shutdown();
        // Close DB only after all accepted observation writes and upload ACKs finish.
        io.execute(()->{try{while(!network.awaitTermination(1,TimeUnit.SECONDS)){} }catch(InterruptedException e){Thread.currentThread().interrupt();}store.close();});
        io.shutdown();super.onDestroy();}
}
