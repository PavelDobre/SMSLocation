package uk.sensoryunderload.Location.data;

import java.util.ArrayList;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public final class ListItem {
  private String senderName;
  private final String sender;
  private String messagePrefix;
  private boolean ignoreRequests;

  public ListItem(String senderName, String senderNum, String messagePrefix, boolean ignoreRequests) {
    this.senderName = senderName;
    this.sender = senderNum;
    this.messagePrefix = messagePrefix;
    this.ignoreRequests = ignoreRequests;
  }

  @NonNull
  @Override
  public String toString() {
    if (getSenderName() != null) {
      return getSenderName() + " (" + getSenderNum() + ")";
    } else {
      return getSenderNum();
    }
  }

  public String getSenderName() {
    return senderName;
  }

  public String getSenderNum() {
    return sender;
  }

  public String getMessagePrefix() {
    return messagePrefix;
  }

  public boolean getIgnoreRequests() {
    return ignoreRequests;
  }

  public void setSenderName(String senderName) {
    this.senderName = senderName;
  }

  public void setMessagePrefix(String messagePrefix) {
    this.messagePrefix = messagePrefix;
  }

  public void setIgnoreRequests(boolean ignoreRequests) {
    this.ignoreRequests = ignoreRequests;
  }

  public static ArrayList<ListItem> fromJson(String json) {
    ArrayList<ListItem> result;
    Gson gson = new Gson();
    result = gson.fromJson(json, new TypeToken<ArrayList<ListItem>>() {
    }.getType());
    return result;
  }

  public static String toJson(ArrayList<ListItem> arrayList) {
    return (new Gson()).toJson(arrayList);
  }

  private static boolean senderMatches(ListItem item, String sender, Context context) {
    if (item.sender.charAt(0) == '+') {
      // item.sender already has a country code prepended, so matching
      // is simple.
      return (item.sender.equals(sender));
    } else {
      if ((Build.VERSION.SDK_INT >= 31) &&
          ((Build.VERSION.SDK_INT < 33) ||
           context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))) {
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String countryCode = tm.getNetworkCountryIso();
        if ((countryCode != null) && (!countryCode.equals(""))) {
          return PhoneNumberUtils.areSamePhoneNumber(item.sender, sender, countryCode);
        }
      }
    }

    // Fallback - just match the last 7 digits.
    return PhoneNumberUtils.compare(item.sender, sender);
  }

  public static ListItem getMatch(ArrayList<ListItem> listItems,
                                  String sender, Context context) {
    for (ListItem item : listItems) {
      if (senderMatches(item, sender, context)) {
        return item;
      }
    }

    return null;
  }

  public static ListItem getMatch(ArrayList<ListItem> listItems,
                                  String sender, String message, Context context) {
    for (ListItem item : listItems) {
      if (!item.getIgnoreRequests() &&
          senderMatches(item, sender, context) &&
          message.startsWith(item.messagePrefix)) {
        return item;
      }
    }

    return null;
  }
}
