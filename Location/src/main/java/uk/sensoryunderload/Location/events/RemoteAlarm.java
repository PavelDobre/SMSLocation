package uk.sensoryunderload.Location.events;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

import uk.sensoryunderload.Location.R;
import uk.sensoryunderload.Location.activity.MainActivity;
import uk.sensoryunderload.Location.data.Constants;

/** Plays a temporary loud alarm in response to an authorised SMS command. */
public final class RemoteAlarm {
  private static final String TAG = "RemoteAlarm";
  private static final long ALARM_DURATION_MS = 120000;
  // Delay only the STOP notification so it appears after the incoming-SMS
  // notification. The alarm sound itself starts immediately.
  private static final long NOTIFICATION_DELAY_MS = 3000;

  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static MediaPlayer player = null;
  private static AudioManager audioManager = null;
  private static Context alarmContext = null;
  private static int previousAlarmVolume = -1;
  private static Runnable stopRunnable = null;
  private static Runnable notificationRunnable = null;
  private static boolean notificationShown = false;

  private RemoteAlarm() {}

  public static synchronized boolean isRunning() {
    if (player == null) {
      return false;
    }
    try {
      return player.isPlaying();
    } catch (IllegalStateException exception) {
      return false;
    }
  }

  public static synchronized void start(Context context) {
    Context appContext = context.getApplicationContext();

    // A repeated RING command extends the alarm. STOP is an explicit command,
    // so delayed/duplicated SMS messages cannot accidentally toggle it off.
    if (isRunning()) {
      scheduleStop();
      // If the STOP notification is already visible, keep it. If it is still
      // waiting for the initial delay, do not postpone it again.
      if (!notificationShown && notificationRunnable == null) {
        scheduleNotification(appContext);
      }
      Log.i(TAG, "Remote alarm already active; timeout extended");
      return;
    }

    // Release a stale player instance if one exists in a non-playing state.
    releasePlayer();
    alarmContext = appContext;
    audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);

    if (audioManager != null) {
      previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
      int maximumVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
      try {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maximumVolume, 0);
      } catch (SecurityException exception) {
        // Some Do Not Disturb configurations can reject volume changes.
        Log.w(TAG, "Unable to raise alarm volume", exception);
      }
    }

    try (AssetFileDescriptor descriptor =
                 appContext.getResources().openRawResourceFd(R.raw.remote_alarm)) {
      if (descriptor == null) {
        restoreVolume();
        alarmContext = null;
        return;
      }

      MediaPlayer mediaPlayer = new MediaPlayer();
      mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_ALARM)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build());
      mediaPlayer.setDataSource(descriptor.getFileDescriptor(),
                                descriptor.getStartOffset(),
                                descriptor.getLength());
      mediaPlayer.setLooping(true);
      mediaPlayer.setVolume(1.0f, 1.0f);
      mediaPlayer.prepare();
      mediaPlayer.start();
      player = mediaPlayer;
      scheduleStop();
      scheduleNotification(appContext);
      Log.i(TAG, "Remote alarm started; STOP notification scheduled");
    } catch (IOException | RuntimeException exception) {
      Log.e(TAG, "Unable to start remote alarm", exception);
      releasePlayer();
      restoreVolume();
      cancelNotification();
      alarmContext = null;
    }
  }

  public static synchronized void stop() {
    if (notificationRunnable != null) {
      handler.removeCallbacks(notificationRunnable);
      notificationRunnable = null;
    }
    if (stopRunnable != null) {
      handler.removeCallbacks(stopRunnable);
      stopRunnable = null;
    }
    releasePlayer();
    restoreVolume();
    cancelNotification();
    alarmContext = null;
    Log.i(TAG, "Remote alarm stopped");
  }

  private static void scheduleStop() {
    if (stopRunnable != null) {
      handler.removeCallbacks(stopRunnable);
    }
    stopRunnable = () -> {
      stopRunnable = null;
      stop();
    };
    handler.postDelayed(stopRunnable, ALARM_DURATION_MS);
  }

  private static void scheduleNotification(Context context) {
    if (notificationShown || notificationRunnable != null) {
      return;
    }

    Context appContext = context.getApplicationContext();
    notificationRunnable = () -> {
      synchronized (RemoteAlarm.class) {
        notificationRunnable = null;
        if (!isRunning()) {
          return;
        }
        showNotification(appContext);
      }
    };
    handler.postDelayed(notificationRunnable, NOTIFICATION_DELAY_MS);
  }

  private static void showNotification(Context context) {
    NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel channel = new NotificationChannel(
              Constants.ALARM_NOTIFICATION_CHANNEL_ID,
              context.getString(R.string.alarm_notification_channel),
              NotificationManager.IMPORTANCE_HIGH);
      channel.setSound(null, null);
      channel.enableVibration(false);
      manager.createNotificationChannel(channel);
    }

    Intent stopIntent = new Intent(context, AlarmActionReceiver.class)
            .setAction(AlarmActionReceiver.ACTION_STOP_ALARM);
    PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    PendingIntent openAppIntent = PendingIntent.getActivity(
            context,
            0,
            new Intent(context, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    NotificationCompat.Builder builder = new NotificationCompat.Builder(
            context, Constants.ALARM_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart_24)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(context.getString(R.string.alarm_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_ring_24,
                       context.getString(R.string.stop_alarm_button),
                       stopPendingIntent);

    manager.notify(Constants.ALARM_NOTIFICATION_ID, builder.build());
    notificationShown = true;
  }

  private static void cancelNotification() {
    if (alarmContext == null) {
      return;
    }
    NotificationManager manager = (NotificationManager)
            alarmContext.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager != null) {
      manager.cancel(Constants.ALARM_NOTIFICATION_ID);
    }
    notificationShown = false;
  }

  private static void releasePlayer() {
    if (player != null) {
      try {
        player.stop();
      } catch (IllegalStateException ignored) {
      }
      try {
        player.release();
      } catch (RuntimeException ignored) {
      }
      player = null;
    }
  }

  private static void restoreVolume() {
    if (audioManager != null && previousAlarmVolume >= 0) {
      try {
        audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
      } catch (SecurityException exception) {
        Log.w(TAG, "Unable to restore alarm volume", exception);
      }
    }
    previousAlarmVolume = -1;
    audioManager = null;
  }
}
