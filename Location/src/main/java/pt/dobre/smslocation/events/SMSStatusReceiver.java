package pt.dobre.smslocation.events;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;

public class SMSStatusReceiver extends BroadcastReceiver {
  private static final String TAG = "SMSStatus";

  public static final String ACTION_SENT =
          "pt.dobre.smslocation.SMS_SENT";
  public static final String ACTION_DELIVERED =
          "pt.dobre.smslocation.SMS_DELIVERED";

  public static final String EXTRA_RECIPIENT = "recipient";
  public static final String EXTRA_PART = "part";
  public static final String EXTRA_TOTAL = "total";

  @Override
  public void onReceive(Context context, Intent intent) {
    String recipient = intent.getStringExtra(EXTRA_RECIPIENT);
    int part = intent.getIntExtra(EXTRA_PART, 1);
    int total = intent.getIntExtra(EXTRA_TOTAL, 1);

    if (ACTION_SENT.equals(intent.getAction())) {
      if (getResultCode() == Activity.RESULT_OK) {
        Log.i(TAG, "SMS sent successfully to " + recipient +
                " (part " + part + "/" + total + ")");
      } else {
        Log.e(TAG, "SMS send failed to " + recipient +
                " (part " + part + "/" + total +
                "), result=" + resultName(getResultCode()));
      }
    } else if (ACTION_DELIVERED.equals(intent.getAction())) {
      Log.i(TAG, "SMS delivery callback for " + recipient +
              " (part " + part + "/" + total +
              "), resultCode=" + getResultCode());
    }
  }

  private String resultName(int resultCode) {
    switch (resultCode) {
      case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
        return "GENERIC_FAILURE";
      case SmsManager.RESULT_ERROR_NO_SERVICE:
        return "NO_SERVICE";
      case SmsManager.RESULT_ERROR_NULL_PDU:
        return "NULL_PDU";
      case SmsManager.RESULT_ERROR_RADIO_OFF:
        return "RADIO_OFF";
      default:
        return Integer.toString(resultCode);
    }
  }
}
