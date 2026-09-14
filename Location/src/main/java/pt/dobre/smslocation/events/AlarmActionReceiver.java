package pt.dobre.smslocation.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles local actions from the remote-alarm notification. */
public final class AlarmActionReceiver extends BroadcastReceiver {
  public static final String ACTION_STOP_ALARM =
          "pt.dobre.smslocation.action.STOP_REMOTE_ALARM";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent != null && ACTION_STOP_ALARM.equals(intent.getAction())) {
      RemoteAlarm.stop();
    }
  }
}
