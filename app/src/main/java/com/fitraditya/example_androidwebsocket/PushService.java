package com.fitraditya.example_androidwebsocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.util.Log;

import com.fitraditya.androidwebsocket.WebsocketClient;

import java.net.URI;
import java.util.HashSet;

/**
 * Created by fitra on 07/06/17.
 */

public class PushService extends Service implements WebsocketClient.WebsocketListener {
    private static final String ACTION_PING = "WS_SVC.ACTION_PING";
    private static final String ACTION_CONNECT = "WS_SVC.ACTION_CONNECT";
    private static final String ACTION_SHUT_DOWN = "WS_SVC.ACTION_SHUT_DOWN";
    private static final String WS_SERVER = "wss://echo.websocket.org";

    private final IBinder iBinder = new ServiceBinder();
    private WebsocketClient websocketClient;
    private HashSet<String> list = new HashSet<>();
    private Handler handler;
    private PushListener pushListener;
    private boolean isShutdown = false;

    public interface PushListener{
        void newMessage(String message);
    }

    public class ServiceBinder extends Binder{
        PushService getService(){
            return PushService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return iBinder;
    }

    public static Intent startIntent(Context context){
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_CONNECT);
        return i;
    }

    public static Intent pingIntent(Context context){
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_PING);
        return i;
    }

    public static Intent closeIntent(Context context){
        Intent i = new Intent(context, PushService.class);
        i.setAction(ACTION_SHUT_DOWN);
        return i;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler();
        Log.i("WS_SVC", "Creating service: " + this.toString());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i("WS_SVC", "Destroying service: " + this.toString());

        if (websocketClient != null && websocketClient.isConnected()) {
            websocketClient.disconnect();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        PowerManager.WakeLock wakelock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WS_SVC.PUSH_SVC");
        wakelock.acquire();

        if (intent != null) {
            Log.i("WS_SVC", intent.toUri(0));
        }

        isShutdown = false;

        if (websocketClient == null) {
            PowerManager.WakeLock clientlock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WS_SVC.WS_SVC");
            websocketClient = new WebsocketClient(URI.create(WS_SERVER), this, null, clientlock);
        }

        if (!websocketClient.isConnected()) {
            websocketClient.connect();
        }

        if (intent != null) {
            if (ACTION_PING.equals(intent.getAction())) {
                if (websocketClient.isConnected()) {
                    websocketClient.send("{\"action\":\"ping\"}");
                }
            } else if (ACTION_SHUT_DOWN.equals(intent.getAction())) {
                isShutdown = true;

                if (websocketClient.isConnected()) {
                    websocketClient.disconnect();
                }
            }
        }

        if (intent == null || !ACTION_SHUT_DOWN.equals(intent.getAction())) {
            AlarmManager alarmManager = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
            PendingIntent pendingIntent = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_NO_CREATE);

            if (pendingIntent == null) {
                pendingIntent = PendingIntent.getService(this, 0, PushService.pingIntent(this), PendingIntent.FLAG_UPDATE_CURRENT);
                alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), AlarmManager.INTERVAL_HALF_HOUR, pendingIntent);
            }
        }

        wakelock.release();

        return START_STICKY;
    }

    public synchronized void setListener(PushListener listener) {
        pushListener = listener;
    }


    public synchronized boolean isConnected() {
        return websocketClient != null && websocketClient.isConnected();
    }

    @Override
    public void onConnect() {
        Log.d("WS_SVC", "Connected to websocket");

    }

    @Override
    public void onMessage(final String message) {
        PowerManager.WakeLock wakelock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EECS780 Service");
        wakelock.acquire();

        Log.d("WS_SVC", "Message: " + message);

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (pushListener != null) {
                    pushListener.newMessage(message);
                }
            }
        });

        wakelock.release();
    }

    @Override
    public void onMessage(byte[] data) {
        //
    }

    @Override
    public void onDisconnect(int code, String reason) {
        Log.d("WS_SVC", String.format("Disconnected from server. Code: %d, reason: %s", code, reason));

        if (!isShutdown) {
            startService(startIntent(this));
        } else {
            stopSelf();
        }
    }

    @Override
    public void onError(Exception error) {
        Log.e("WS_SVC", "Error:", error);
        startService(startIntent(this));
    }
}
