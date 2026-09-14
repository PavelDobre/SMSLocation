package pt.dobre.smslocation.events;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import pt.dobre.smslocation.data.ListItem;
import pt.dobre.smslocation.data.Preferences;

import java.util.ArrayList;

public class SMSReceiver extends BroadcastReceiver {
  private static final String TAG = "SMSReceiver";

  @Override
  public void onReceive(Context context, Intent intent) {
    if (!Preferences.isServiceEnabled(context) || intent == null ||
        !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
      return;
    }

    final ArrayList<ListItem> listItems = Preferences.getListItems(context);
    if (listItems.isEmpty()) {
      return;
    }

    final SmsMessage[] messages;
    try {
      // Let Android decode the PDU/format. This is more robust across real
      // devices and modem implementations than decoding the raw extras here.
      messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
    } catch (RuntimeException exception) {
      Log.e(TAG, "Unable to decode incoming SMS", exception);
      return;
    }

    if (messages == null) {
      return;
    }

    for (SmsMessage message : messages) {
      if (message == null || message.getOriginatingAddress() == null) {
        continue;
      }

      String sender = message.getOriginatingAddress().trim();
      String rawBody = message.getMessageBody();
      if (sender.isEmpty() || rawBody == null) {
        continue;
      }
      String body = rawBody.trim();

      ListItem alarmItem = ListItem.getAlarmMatch(listItems, sender, body, context);
      if (alarmItem != null) {
        try {
          if (ListItem.isAlarmStopMessage(alarmItem, body)) {
            Log.i(TAG, "Remote alarm STOP request received from " + sender);
            RemoteAlarm.stop();
          } else {
            Log.i(TAG, "Remote alarm request received from " + sender);
            RemoteAlarm.start(context);
          }
        } catch (RuntimeException exception) {
          // A malformed audio state or vendor-specific audio implementation
          // must never crash the SMS receiver/process.
          Log.e(TAG, "Remote alarm command failed", exception);
        }
        return;
      }

      ListItem item = ListItem.getMatch(listItems, sender, body, context);
      if (item == null) {
        continue;
      }

      // Do not log the SMS body: request prefixes are secret commands.
      Log.i(TAG, "Location request received from " + sender);
      try {
        GPSSender.notify(context, sender);
      } catch (RuntimeException exception) {
        Log.e(TAG, "Location request failed", exception);
      }
      return;
    }
  }
}
