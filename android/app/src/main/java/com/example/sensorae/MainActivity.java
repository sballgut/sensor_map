package com.example.sensorae;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
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

/** UI for process-owned collection. Leaving this Activity never stops CollectionService. */
public final class MainActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private ExecutorService io,network,inference;
    public TeamBridge bridge;
    private CollectionEngine engine;
    private final Gson gson=new Gson();
    private ObservationStore store;
    private PublicApi publicApi;
    private ReleaseInstaller releases;
    private SharedPreferences prefs;
    private Spinner mode;
    private CheckBox consent;
    private EditText url,token,declination,reference,description;
    private TextView summary,logView,mapView;
    private ViewFlipper screens;
    private DestinationHomeView destinationHome;
    private String activeMode="guidance";
    private volatile boolean destroyed;
    private boolean replaying,restoring;
    private volatile long generation;
    private int exportKind;
    private final Runnable statusTick=new Runnable(){public void run(){
        if(destroyed)return;
        updateControls();
        if(!replaying)summary.setText(engine.status());
        logView.setText(engine.diagnostics());
        main.postDelayed(this,2000);
    }};
    private void updateControls(){
        boolean collecting=engine.running;
        mode.setEnabled(!collecting);consent.setEnabled(!collecting);
        declination.setEnabled(!collecting);reference.setEnabled(!collecting);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        engine=CollectionEngine.get(this);prefs=engine.prefs;
        store=engine.store;releases=engine.releases;bridge=engine.bridge;
        io=engine.io;network=engine.network;inference=engine.inference;
        publicApi=new PublicApi(this);
        buildUi();
        destinationHome.restoreSelection(state);
        if(engine.running||getIntent().getBooleanExtra("show_collection",false))screens.setDisplayedChild(1);
        else if(state!=null)screens.setDisplayedChild(state.getBoolean("d1.collection",false)?1:0);
        io.execute(()->{try{List<PublicApi.SensorSnapshot> cached=publicApi.cachedMap();main.post(()->renderMap(cached,"캐시"));}catch(Exception ex){note("캐시 없음 · 센서 목록 새로고침을 누르세요.");}});
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
        TextView intro=new TextView(this);intro.setText("34개 센서 레지스트리 / 주변 BLE 관측 / 팀 서버 전송\n수집 시작 후 화면을 꺼도 계속 수집합니다. 앱 또는 알림에서 중지하세요.\n안내는 GPS 미사용 모드입니다. 실제 길 안내 UI는 B·C·D 연동 후 추가합니다.");box.addView(intro);
        mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"안내 · GPS 미사용","수집 · GPS 라벨","평가 · GPS 라벨"}));box.addView(mode);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){} public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(engine.running)return;activeMode=new String[]{"guidance","collection","evaluation"}[pos];}});
        reference=field(box,"기준 지점 ID (선택)","");
        declination=field(box,"고정 자북 보정각(도) · 미확인 시 0",prefs.getString("declination","0"));
        consent=new CheckBox(this);consent.setText("이 세션을 경로 후보 근거로 기록하는 데 동의");box.addView(consent);
        consent.setOnCheckedChangeListener((b,checked)->{if(checked&&!restoring)new AlertDialog.Builder(this).setTitle("후보 기록 동의").setMessage("BLE 관측, B가 제공한 추정 위치·불확실성을 세션에 연결해 저장합니다. 서버 제출은 별도 버튼입니다. 동의를 끄면 이후 세션의 후보 기록을 만들지 않습니다. 일반 실험 관측은 동의 여부와 관계없이 로컬 저장됩니다.").setPositiveButton("동의",(d,w)->{}).setNegativeButton("취소",(d,w)->consent.setChecked(false)).setOnCancelListener(d->consent.setChecked(false)).show();});
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
        if(engine.running){
            restoring=true;activeMode=engine.activeMode;
            mode.setSelection(activeMode.equals("collection")?1:activeMode.equals("evaluation")?2:0);
            consent.setChecked(engine.sessionConsent);reference.setText(engine.referencePoint);
            declination.setText(Double.toString(engine.correction));restoring=false;
        }
        updateControls();
    }
    private EditText field(LinearLayout box,String hint,String value){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine();e.setText(value);box.addView(e);return e;}
    private void button(LinearLayout box,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());box.addView(b);}
    private void startRequested(){
        if(engine.running){note("이미 수집 중입니다.");return;}
        replaying=false;generation++;
        List<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=31){for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)missing.add(p);}
        // BLE observations are used for physical location inference, so neverForLocation
        // is not appropriate. Permission does not enable GPS providers in guidance mode.
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){missing.add(Manifest.permission.ACCESS_FINE_LOCATION);missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),100);return;}
        start();
    }
    @Override public void onRequestPermissionsResult(int c,String[] p,int[] g){super.onRequestPermissionsResult(c,p,g);if(c==100){boolean allowed=g.length==p.length&&g.length>0;for(int i=0;i<Math.min(p.length,g.length);i++){if(!p[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)&&!p[i].equals(Manifest.permission.POST_NOTIFICATIONS)&&g[i]!=PackageManager.PERMISSION_GRANTED)allowed=false;}if(allowed)start();else note("주변 기기와 정확한 위치 권한을 확인하고 다시 시작하세요. BLE 위치 추정에 권한이 필요하지만 안내 모드는 GPS 좌표를 요청하지 않습니다.");}}
    private void start(){
        double correction;try{correction=Double.parseDouble(declination.getText().toString());if(!Double.isFinite(correction)||Math.abs(correction)>180)throw new Exception();}catch(Exception e){note("보정각을 -180~180 범위로 입력하세요.");return;}
        if(engine.running){note("이미 수집 중입니다.");return;}
        prefs.edit().putString("server_url",url.getText().toString().trim()).putString("server_token",token.getText().toString().trim()).putString("declination",declination.getText().toString()).apply();
        Intent intent=new Intent(this,CollectionService.class).setAction(CollectionService.START)
            .putExtra("mode",activeMode).putExtra("consent",consent.isChecked())
            .putExtra("reference",reference.getText().toString().trim()).putExtra("correction",correction);
        try{startForegroundService(intent);note("수집 시작 요청 · 화면을 꺼도 수집합니다. 알림에서 중지할 수 있습니다.");}
        catch(RuntimeException error){note("수집 시작 실패 · "+safe(error));}
    }
    private void stop(){
        replaying=false;generation++;
        engine.stop();stopService(new Intent(this,CollectionService.class));
        updateControls();
    }
    private void sync(){
        if(!serverPermission(false)){note("로컬 네트워크 권한 필요 · 배치 전송 버튼을 누르세요");return;}
        engine.sync(url.getText().toString().trim(),token.getText().toString().trim());
    }
    private void renderMap(List<PublicApi.SensorSnapshot> snapshots,String origin){
        if(destroyed)return;
        Map<String,PublicApi.SensorSnapshot> all=new HashMap<>();for(PublicApi.SensorSnapshot s:snapshots)all.put(s.sensor_id,s);
        StringBuilder text=new StringBuilder("공용 서버 최신값 · "+origin+" · "+all.size()+"개\n설치 좌표는 휴대폰 GPS와 별개입니다. API에 없는 센서는 미제공으로 표시합니다.\n");
        for(int i=1;i<=34;i++){String id=String.format(Locale.US,"sensor %02d",i);PublicApi.SensorSnapshot s=all.get(id);text.append(id).append(" · ");if(s==null)text.append("API 미제공");else text.append(s.temperature).append("°C / CO₂ ").append(s.co2).append(" · ").append(s.fresh?"최근 1시간":"오래된 값").append("\n  설치 ").append(s.latitude).append(", ").append(s.longitude).append(" · ").append(s.mode).append(" · ").append(s.time);text.append('\n');}
        mapView.setText(text.toString());
    }
    private void replay(){String id=prefs.getString("last_session",null);if(id==null){note("저장된 세션이 없습니다.");return;}io.execute(()->{try{List<Observation> rows=store.session(id);main.post(()->{if(engine.running){note("수집을 중지한 뒤 재생하세요.");return;}long current=++generation;inference.execute(()->{if(!destroyed&&current==generation)bridge.clear();});replaying=true;note("재생 시작 · "+rows.size()+"건 · 실제 전송/재저장 없음");replayRows(rows,0,current);});}catch(Exception e){note("재생 오류 · "+safe(e));}});}
    private void replayRows(List<Observation> rows,int i,long current){if(destroyed||current!=generation||engine.running)return;if(i>=rows.size()){replaying=false;note("재생 완료");return;}Observation o=rows.get(i);inference.execute(()->{if(!destroyed&&current==generation)bridge.accept(o);});summary.setText("재생 "+(i+1)+"/"+rows.size()+"\n"+o.sensor_id+" RSSI "+o.rssi+" · "+o.parse_status);long delay=i+1<rows.size()?Math.min(2000,Math.max(10,(rows.get(i+1).elapsed_ns-o.elapsed_ns)/1_000_000)):10;main.postDelayed(()->replayRows(rows,i+1,current),delay);}
    private void submitCandidate(){
        if(engine.sessionId==null||!engine.sessionConsent){note("후보 기록에 동의하고 수집한 세션이 필요합니다.");return;}
        final String sid=engine.sessionId,base=url.getText().toString().trim(),key=token.getText().toString().trim(),desc=description.getText().toString().trim();
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
    private void note(String text){engine.note(text);main.post(()->{if(!destroyed&&logView!=null)logView.setText(engine.diagnostics());});}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(destinationHome!=null)destinationHome.saveSelection(state);if(screens!=null)state.putBoolean("d1.collection",screens.getDisplayedChild()==1);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("show_collection",false))screens.setDisplayedChild(1);}
    @Override protected void onStart(){super.onStart();main.removeCallbacks(statusTick);main.post(statusTick);}
    @Override protected void onStop(){main.removeCallbacks(statusTick);replaying=false;generation++;super.onStop();}
    @Override protected void onDestroy(){destroyed=true;main.removeCallbacksAndMessages(null);super.onDestroy();}
}
