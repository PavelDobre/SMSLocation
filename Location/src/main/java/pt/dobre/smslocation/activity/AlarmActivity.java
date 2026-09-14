package pt.dobre.smslocation.activity;

import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import pt.dobre.smslocation.R;
import pt.dobre.smslocation.events.RemoteAlarm;

/**
 * Full-screen alarm screen.
 *
 * <p>Started by the remote-alarm notification through
 * {@code setFullScreenIntent()}. The system launches it directly when the
 * screen is off or the device is locked, which is exactly the case where
 * finding the STOP action inside a collapsed notification group is hardest.
 * When the device is unlocked and in use, the system falls back to the normal
 * heads-up notification and this activity is never shown.
 *
 * <p>The screen deliberately contains nothing but a title and one very large
 * STOP button: it is visible above the lock screen to anyone holding the
 * phone, so no location data is displayed here.
 */
public final class AlarmActivity extends AppCompatActivity
        implements RemoteAlarm.StateListener {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    // Must be configured before the window is created.
    showOverLockScreen();

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_alarm);

    findViewById(R.id.stop_alarm_button).setOnClickListener(v -> {
      RemoteAlarm.stop();
      finish();
    });

    // The alarm can also be stopped from the notification action, by a remote
    // "<PREFIX> STOP" SMS, or by the two-minute timeout. In all those cases
    // this screen has to disappear on its own.
    RemoteAlarm.setStateListener(this);

    // Guard against the race where the alarm has already stopped between the
    // notification being posted and this activity being created.
    if (!RemoteAlarm.isRunning()) {
      finish();
    }
  }

  private void showOverLockScreen() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    } else {
      // minSdkVersion is 23, so the deprecated flags are still needed.
      getWindow().addFlags(
              WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
              WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
              WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  public void onAlarmStopped() {
    finish();
  }

  /**
   * Back must silence the alarm rather than just hide this screen. Closing the
   * only visible STOP control while the sound keeps playing is the worst
   * possible outcome for the user.
   */
  @SuppressWarnings("deprecation")
  @Override
  public void onBackPressed() {
    RemoteAlarm.stop();
    super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    RemoteAlarm.clearStateListener(this);
    super.onDestroy();
  }
}
