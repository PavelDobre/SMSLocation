package pt.dobre.smslocation.events;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.telephony.SmsManager;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class SMSSender {
  private static final String TAG = "SMSSender";

  // A concatenated UCS-2 SMS normally has room for 67 UTF-16 code units after
  // the concatenation header. Android reduces this to 66 for carriers without
  // EMS support, so the fallback deliberately uses the conservative 66-char
  // limit. Location replies always contain Cyrillic labels,
  // so they are encoded as Unicode. This fallback is only used on Android/
  // vendor builds where SmsManager.divideMessage() incorrectly tries to read
  // telephony subscriber data and throws SecurityException without
  // READ_PHONE_STATE.
  private static final int FALLBACK_UNICODE_PART_LENGTH = 66;

  private final Context context;
  private final String recipient;
  private final Location location;
  private final String descriptionString;

  public SMSSender(Context context, String recipient, Location location,
                   String descriptionString) {
    this.context = context.getApplicationContext();
    this.recipient = recipient;
    this.location = location;
    this.descriptionString = descriptionString;
  }

  public void sendMessage() {
    SmsManager smsManager = SmsManager.getDefault();
    String message = generateBasicMessage();

    ArrayList<String> messages;
    try {
      // Keep using Android's normal splitter whenever possible. This preserves
      // the exact behaviour that the original application used for years.
      messages = smsManager.divideMessage(message);
    } catch (SecurityException exception) {
      // Some Android/vendor telephony implementations call
      // TelephonyManager.getGroupIdLevel1() from divideMessage(), which requires
      // READ_PHONE_STATE. SMSLocation does not need that sensitive permission,
      // so fall back to a local Unicode-safe splitter instead of crashing.
      Log.w(TAG, "SmsManager.divideMessage() was denied; using local multipart splitter", exception);
      messages = divideUnicodeMessage(message);
    } catch (RuntimeException exception) {
      Log.w(TAG, "SmsManager.divideMessage() failed; using local multipart splitter", exception);
      messages = divideUnicodeMessage(message);
    }

    if (messages.isEmpty()) {
      Log.e(TAG, "Location response is empty; SMS not sent");
      return;
    }

    ArrayList<PendingIntent> sentIntents = new ArrayList<>();
    ArrayList<PendingIntent> deliveryIntents = new ArrayList<>();

    Log.i(TAG, "Sending location response to " + recipient +
            " in " + messages.size() + " SMS part(s)");

    for (int index = 0; index < messages.size(); index++) {
      int requestCode = (int) (System.currentTimeMillis() & 0x0fffffff) + index;

      Intent sent = new Intent(context, SMSStatusReceiver.class)
              .setAction(SMSStatusReceiver.ACTION_SENT)
              .putExtra(SMSStatusReceiver.EXTRA_RECIPIENT, recipient)
              .putExtra(SMSStatusReceiver.EXTRA_PART, index + 1)
              .putExtra(SMSStatusReceiver.EXTRA_TOTAL, messages.size());
      sentIntents.add(PendingIntent.getBroadcast(
              context, requestCode, sent,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

      Intent delivered = new Intent(context, SMSStatusReceiver.class)
              .setAction(SMSStatusReceiver.ACTION_DELIVERED)
              .putExtra(SMSStatusReceiver.EXTRA_RECIPIENT, recipient)
              .putExtra(SMSStatusReceiver.EXTRA_PART, index + 1)
              .putExtra(SMSStatusReceiver.EXTRA_TOTAL, messages.size());
      deliveryIntents.add(PendingIntent.getBroadcast(
              context, requestCode + 10000, delivered,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    try {
      smsManager.sendMultipartTextMessage(
              recipient, null, messages, sentIntents, deliveryIntents);
    } catch (SecurityException exception) {
      Log.e(TAG, "SMS permission denied while sending location response to " + recipient, exception);
    } catch (RuntimeException exception) {
      Log.e(TAG, "Unable to submit location SMS to " + recipient, exception);
    }
  }

  private static ArrayList<String> divideUnicodeMessage(String text) {
    ArrayList<String> parts = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return parts;
    }

    int start = 0;
    while (start < text.length()) {
      int end = Math.min(start + FALLBACK_UNICODE_PART_LENGTH, text.length());

      // Do not split a UTF-16 surrogate pair between SMS parts.
      if (end < text.length() && end > start &&
              Character.isHighSurrogate(text.charAt(end - 1)) &&
              Character.isLowSurrogate(text.charAt(end))) {
        end--;
      }

      parts.add(text.substring(start, end));
      start = end;
    }
    return parts;
  }

  private String generateBasicMessage() {
    if (location != null) {
      double lat = location.getLatitude();
      double lon = location.getLongitude();
      float accuracy = location.getAccuracy();
      float speed = location.getSpeed();
      String currentTime = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
              .format(new Date());
      return String.format(
              """
                      %1$s:
                      Google: https://www.google.com/maps/search/?api=1&query=%2$s,%3$s
                      Точность: %4$s m
                      Скорость: %5$s km/h
                      Координаты: (N,W): %2$s,%3$s
                      Время: %6$s
                      """, //+
           //  "OSM: https://www.openstreetmap.org/?mlat=%2$s&mlon=%3$s&zoom=19",
              descriptionString,
              String.format(Locale.US, "%.6f", lat),
              String.format(Locale.US, "%.6f", lon),
              Math.round(accuracy),
              Math.round(3.6 * speed),
              currentTime);
    } else {
      return descriptionString;
    }
  }
}
