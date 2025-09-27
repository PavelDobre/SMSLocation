package uk.sensoryunderload.Location.data;

public class Constants {
  // Keys in intents used to forward data between activities.
  public static final String ITEM_ID_KEY = "item_id";
  public static final String SENDER_NAME_KEY = "sender_name";
  public static final String SENDER_NUM_KEY = "sender";
  public static final String MESSAGE_KEY = "message";
  public static final String IGNORE_KEY = "ignore";

  // Activities request codes.
  public static final int EDIT_ITEM_REQUEST_CODE = 520;
  public static final int CONTACT_PICKER_REQUEST_CODE = 521;

  // Activities result codes.
  public static final int EDIT_ITEM_ADD_RESULT_CODE = 540;
  public static final int EDIT_ITEM_REMOVE_RESULT_CODE = 541;

  // Permissions request codes.
  public static final int LOCATION_SMS_PERMISSIONS_REQUEST_CODE = 560;
  public static final int CONTACTS_PERMISSION_REQUEST_CODE = 561;

  // Permissions arrays.
  public static final String[] LOCATION_AND_SMS_PERMISSIONS = {
          "android.permission.SEND_SMS",
          "android.permission.RECEIVE_SMS",
          "android.permission.ACCESS_COARSE_LOCATION",
          "android.permission.ACCESS_FINE_LOCATION"};
  public static final String[] CONTACTS_PERMISSION =
          {"android.permission.READ_CONTACTS"};

  // Notification settings for the foreground service.
  public static final String NOTIFICATION_CHANNEL_ID = "ForegroundService";
  public static final int NOTIFICATION_ID = 546;
}
