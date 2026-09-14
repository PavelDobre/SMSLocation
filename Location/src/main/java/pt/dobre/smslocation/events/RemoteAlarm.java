package pt.dobre.smslocation.events;

import android.app.KeyguardManager;
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
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.lang.reflect.Method;

import pt.dobre.smslocation.R;
import pt.dobre.smslocation.activity.AlarmActivity;
import pt.dobre.smslocation.activity.MainActivity;
import pt.dobre.smslocation.data.Constants;

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
  private static StateListener stateListener = null;

  /** Lets the full-screen alarm UI close itself when the alarm ends. */
  public interface StateListener {
    void onAlarmStopped();
  }

  private RemoteAlarm() {}

  public static synchronized void setStateListener(StateListener listener) {
    stateListener = listener;
  }

  public static synchronized void clearStateListener(StateListener listener) {
    if (stateListener == listener) {
      stateListener = null;
    }
  }

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

    // Posted rather than called inline: this method is synchronized and the
    // listener is free to call back into RemoteAlarm.
    final StateListener listener = stateListener;
    if (listener != null) {
      handler.post(listener::onAlarmStopped);
    }

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
    final boolean fullScreen = canUseFullScreenIntent(appContext);

    if (fullScreen && isDeviceIdle(appContext)) {
      // Screen off or locked: the incoming-SMS notification is not visible to
      // anyone, so there is nothing to wait for. Post immediately and let the
      // system raise AlarmActivity over the lock screen.
      Log.i(TAG, "Device idle; showing full-screen alarm immediately");
      showNotification(appContext, true);
      return;
    }

    // Device in use: keep the original delay so that the STOP notification
    // appears after the incoming-SMS notification instead of under it.
    notificationRunnable = () -> {
      synchronized (RemoteAlarm.class) {
        notificationRunnable = null;
        if (!isRunning()) {
          return;
        }
        showNotification(appContext, fullScreen);
      }
    };
    handler.postDelayed(notificationRunnable, NOTIFICATION_DELAY_MS);
  }

  private static boolean isDeviceIdle(Context context) {
    PowerManager power =
            (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (power != null && !power.isInteractive()) {
      return true;
    }

    KeyguardManager keyguard =
            (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    return keyguard != null && keyguard.isKeyguardLocked();
  }

  /**
   * USE_FULL_SCREEN_INTENT is granted automatically below API 34. From
   * Android 14 onwards only calling and alarm-clock apps keep it by default,
   * and the user can revoke it in system settings.
   *
   * <p>Reflection is used because the project compiles against SDK 33. Once
   * compileSdkVersion is raised to 34 or later this can become a plain call to
   * {@code NotificationManager.canUseFullScreenIntent()}.
   */
  private static boolean canUseFullScreenIntent(Context context) {
    if (Build.VERSION.SDK_INT < 34) {
      return true;
    }

    NotificationManager manager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    if (manager == null) {
      return false;
    }

    try {
      Method method = NotificationManager.class.getMethod("canUseFullScreenIntent");
      Object result = method.invoke(manager);
      return !(result instanceof Boolean) || (Boolean) result;
    } catch (ReflectiveOperationException | RuntimeException exception) {
      Log.w(TAG, "Unable to query full-screen intent permission", exception);
      return true;
    }
  }

  private static void showNotification(Context context, boolean fullScreen) {
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
            .setSmallIcon(R.drawable.ic_ring_24)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(context.getString(R.string.alarm_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // Shown in full on the lock screen: the text carries no location
            // data, only the STOP instruction.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_ring_24,
                       context.getString(R.string.stop_alarm_button),
                       stopPendingIntent);

    if (fullScreen) {
      Intent alarmScreen = new Intent(context, AlarmActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      PendingIntent fullScreenIntent = PendingIntent.getActivity(
              context,
              1,
              alarmScreen,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      builder.setFullScreenIntent(fullScreenIntent, true);
    }

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
