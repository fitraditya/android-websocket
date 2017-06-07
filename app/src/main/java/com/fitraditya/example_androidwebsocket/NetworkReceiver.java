package com.fitraditya.example_androidwebsocket;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

/**
 * Created by fitra on 07/06/17.
 */

public class NetworkReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ConnectivityManager connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

        if (networkInfo != null && networkInfo.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
            Log.i("WS_SVC", "Connected");
            context.startService(PushService.startIntent(context.getApplicationContext()));
        } else if (networkInfo != null){
            NetworkInfo.DetailedState state = networkInfo.getDetailedState();
            Log.i("WS_SVC", state.name());
        } else {
            Log.i("WS_SVC", "Disconnected");
            AlarmManager alarmManager = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent operation = PendingIntent.getService(context, 0, PushService.pingIntent(context), PendingIntent.FLAG_NO_CREATE);

            if (operation != null) {
                alarmManager.cancel(operation);
                operation.cancel();
            }

            context.startService(PushService.closeIntent(context));
        }
    }
}
