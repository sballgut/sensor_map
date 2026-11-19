package com.example.sensorae;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;

/** User-started foreground collection; no boot receiver or silent restart after a force stop. */
public final class CollectionService extends Service {
    static final String START="com.example.sensorae.START_COLLECTION";
    static final String STOP="com.example.sensorae.STOP_COLLECTION";
    private static final String CHANNEL="sensor_collection";
    private static final int NOTIFICATION=410;
    private CollectionEngine engine;

    @Override public void onCreate(){
        super.onCreate();engine=CollectionEngine.get(this);
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"센서 백그라운드 수집",NotificationManager.IMPORTANCE_LOW));
        engine.onStopped=()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();};
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||!START.equals(intent.getAction())){
            engine.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;
        }
        try{
            Intent open=new Intent(this,MainActivity.class).putExtra("show_collection",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CollectionService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Sensor A·E · 센서 수집 중")
                .setContentText("화면이 꺼져도 수집·저장합니다. 중지를 누르면 종료합니다.")
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null,"수집 중지",stop).build()).build();
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE|ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION,notification);
            engine.start(intent.getStringExtra("mode"),intent.getBooleanExtra("consent",false),
                intent.getStringExtra("reference"),intent.getDoubleExtra("correction",0));
        }catch(RuntimeException error){
            engine.note("수집 시작 실패 · Bluetooth·위치 서비스·권한을 확인하세요 · "+error.getClass().getSimpleName());
            engine.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        engine.onStopped=null;engine.stop();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public void onTimeout(int startId,int fgsType){
        engine.note("시스템이 수집 서비스를 종료했습니다. 앱에서 다시 시작하세요.");
        engine.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
    }
}
