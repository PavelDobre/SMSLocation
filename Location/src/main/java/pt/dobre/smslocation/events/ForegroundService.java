package pt.dobre.smslocation.events;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import pt.dobre.smslocation.R;
import pt.dobre.smslocation.activity.MainActivity;
import pt.dobre.smslocation.data.Constants;

public class ForegroundService extends Service {
  private static final String TAG = "ForegroundService";

  private SMSReceiver smsReceiver;

  @Override
  public void onCreate() {
    super.onCreate();

    // A service started with startForegroundService() must promote itself
    // immediately. Do this before any other initialization to avoid
    // ForegroundServiceDidNotStartInTimeException races.
    startAsForeground();

    smsReceiver = new SMSReceiver();
    registerReceiver(smsReceiver, new IntentFilter(
            "android.provider.Telephony.SMS_RECEIVED"));
    Log.i(TAG, "Registered broadcast receiver");
  }

  private void startAsForeground() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel serviceChannel = new NotificationChannel(
              Constants.NOTIFICATION_CHANNEL_ID,
              getResources().getString(R.string.service_channel),
              NotificationManager.IMPORTANCE_DEFAULT);
      serviceChannel.setShowBadge(false);
      serviceChannel.setSound(null, null);
      serviceChannel.enableVibration(false);
      getSystemService(NotificationManager.class)
              .createNotificationChannel(serviceChannel);
    }

    PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

    Notification notification = new NotificationCompat.Builder(
            this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getResources().getText(R.string.app_name))
            .setContentText(getResources().getText(R.string.service_running))
            .setSmallIcon(R.drawable.ic_heart_24)
            .setContentIntent(pendingIntent)
            .build();

    startForeground(Constants.NOTIFICATION_ID, notification);
    Log.i(TAG, "Service promoted to foreground");
  }

  @Override
  public void onDestroy() {
    RemoteAlarm.stop();
    super.onDestroy();
    if (smsReceiver != null) {
      try {
        unregisterReceiver(smsReceiver);
        Log.i(TAG, "Unregistered broadcast receiver");
      } catch (IllegalArgumentException exception) {
        Log.w(TAG, "SMS receiver was not registered", exception);
      }
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    // startForeground() is intentionally called from onCreate(), before any
    // other work. Repeated starts simply keep this service alive.
    return START_STICKY;
  }
}
