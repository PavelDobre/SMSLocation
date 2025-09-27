package uk.sensoryunderload.Location.events;

import android.location.Location;
import android.telephony.SmsManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class SMSSender {
  private final String recipient;
  private final Location location;
  private final String descriptionString;

  public SMSSender(String recipient, Location location,
                   String descriptionString) {
    this.recipient = recipient;
    this.location = location;
    this.descriptionString = descriptionString;
  }

  public void sendMessage() {
    SmsManager smsManager = SmsManager.getDefault();
    ArrayList<String> messages =
            smsManager.divideMessage(generateBasicMessage());
    smsManager.sendMultipartTextMessage(
            recipient, null, messages, null, null);
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
