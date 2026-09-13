package uk.sensoryunderload.Location.activity;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import uk.sensoryunderload.Location.R;
import uk.sensoryunderload.Location.data.Constants;
import uk.sensoryunderload.Location.data.Preferences;

import com.google.android.material.textfield.TextInputLayout;

import java.util.Objects;

import static android.telephony.PhoneNumberUtils.formatNumberToE164;

public class EditItemActivity extends AppCompatActivity {
  private EditText senderNameInput;
  private EditText senderNumInput;
  private EditText prefixInput;
  private TextInputLayout senderInputLayout;
  private TextInputLayout prefixInputLayout;
  private CheckBox ignoreCheckBox;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    AppCompatDelegate.setDefaultNightMode(Preferences.getTheme(this));
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_edit_item);
    Objects.requireNonNull(getSupportActionBar())
           .setDisplayHomeAsUpEnabled(true);
    Button pickContactButton;
    Button deleteButton;
    Button saveButton;

    senderNameInput = findViewById(R.id.sender_name_input);
    senderNumInput = findViewById(R.id.sender_num_input);
    prefixInput = findViewById(R.id.prefix_input);
    senderInputLayout = findViewById(R.id.sender_num_input_layout);
    prefixInputLayout = findViewById(R.id.prefix_input_layout);
    ignoreCheckBox = findViewById(R.id.ignore_requests_checkbox);
    pickContactButton = findViewById(R.id.pick_contact_button);

    deleteButton = findViewById(R.id.delete_button);
    saveButton = findViewById(R.id.save_button);

    if (!isContactsPermissionGranted()) {
      pickContactButton.setEnabled(false);
      requestContactsPermission();
    }
    pickContactButton.setOnClickListener(v -> startContactPickerActivity());

    int itemId = getIntent().getIntExtra(Constants.ITEM_ID_KEY, -1);
    if (itemId == -1) {
      // We're adding a new item, not editing existing.
      setTitle(R.string.add_item);
      deleteButton.setText(R.string.cancel_button);
    } else {
      // We're editing existing item and must fill the fields with current info.
      String senderName = getIntent().getStringExtra(Constants.SENDER_NAME_KEY);
      String senderNum = getIntent().getStringExtra(Constants.SENDER_NUM_KEY);
      String prefix = getIntent().getStringExtra(Constants.MESSAGE_KEY);
      boolean ignore = getIntent().getBooleanExtra(Constants.IGNORE_KEY, false);

      if (senderName != null) {
        // We guard against setting to null in case we're importing
        // ListItems from legacy versions which don't include the sender
        // name.
        senderNameInput.setText(senderName);
      }
      senderNumInput.setText(senderNum);
      prefixInput.setText(prefix);
      ignoreCheckBox.setChecked(ignore);

      // Only changing the prefix or the name is allowed.
      senderNumInput.setEnabled(false);
      pickContactButton.setEnabled(false);
      pickContactButton.setVisibility(View.GONE);
    }

    senderNumInput.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) {
        senderInputLayout.setError(null);
      }
    });

    prefixInput.setOnFocusChangeListener((v, hasFocus) -> {
      if (hasFocus) {
        prefixInputLayout.setError(null);
      }
    });

    deleteButton.setOnClickListener(v -> {
      Intent result = new Intent(this, MainActivity.class);
      result.putExtra(Constants.ITEM_ID_KEY, getIntent().getIntExtra(
              Constants.ITEM_ID_KEY, -1));
      setResult(Constants.EDIT_ITEM_REMOVE_RESULT_CODE, result);
      finish();
    });

    saveButton.setOnClickListener(v -> {
      if (senderNumInput.getText().toString().isEmpty()) {
        senderInputLayout.setError(getString(R.string.field_empty_label));
        return;
      }
      if (prefixInput.getText().toString().isEmpty()) {
        prefixInputLayout.setError(getString(R.string.field_empty_label));
        return;
      }
      if (senderNumInput.getText().charAt(0) != '+') {
        warnNumberIsntInternational();
        return;
      }
      successCompletion();
    });
  }

  // Return true if the content is to be accepted, possibly with the
  // recommended replacement, else false if the user should be taken
  // back to the form.
  private void warnNumberIsntInternational() {
    Context context = EditItemActivity.this;

    // In case the warning is dismissed
    senderInputLayout.setError(getString(R.string.number_not_international));

    // Attempt to calculate an internationalised number
    String internationalised_number;
    if ((Build.VERSION.SDK_INT >= 31) &&
        ((Build.VERSION.SDK_INT < 33) ||
         context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS))) {
      TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
      String countryCode = tm.getNetworkCountryIso();
      if ((countryCode != null) && (!countryCode.equals(""))) {
        internationalised_number = formatNumberToE164 (senderNumInput.getText().toString(), countryCode.toUpperCase());
      } else {
        internationalised_number = null;
      }
    } else {
      internationalised_number = null;
    }

    // Offer to replace with found internationalisation
    final boolean[] accepted = {false};
    AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
    String message = getString(R.string.encourage_e164_number);
    if (internationalised_number != null) {
      message += "\n\n" + getString(R.string.replace_local_number, internationalised_number);
      dialogBuilder.setPositiveButton(
          R.string.replace_button_text,
              (dialog, id) -> {
                senderNumInput.setText(internationalised_number, TextView.BufferType.EDITABLE);
                successCompletion();
                dialog.dismiss();
              });
    }
    dialogBuilder.setMessage(message);
    dialogBuilder.setNeutralButton(
        R.string.edit_button_text,
            (dialog, id) -> dialog.dismiss());
    dialogBuilder.setNegativeButton(
        R.string.accept_button_text,
            (dialog, id) -> {
              successCompletion();
              dialog.dismiss();
            });
    AlertDialog alertDialog = dialogBuilder.create();
    alertDialog.show();
  }

  private void successCompletion() {
    Intent result = new Intent(this, MainActivity.class);
    result.putExtra(Constants.ITEM_ID_KEY, getIntent().getIntExtra(
            Constants.ITEM_ID_KEY, -1));
    result.putExtra(Constants.SENDER_NAME_KEY,
                    senderNameInput.getText().toString().trim());
    result.putExtra(Constants.SENDER_NUM_KEY,
                    senderNumInput.getText().toString().trim());
    result.putExtra(Constants.MESSAGE_KEY,
                    prefixInput.getText().toString().trim());
    result.putExtra(Constants.IGNORE_KEY,
                    ignoreCheckBox.isChecked());
    setResult(Constants.EDIT_ITEM_ADD_RESULT_CODE, result);
    finish();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private boolean isContactsPermissionGranted() {
    return checkSelfPermission(Constants.CONTACTS_PERMISSION[0]) ==
           PackageManager.PERMISSION_GRANTED;
  }

  private void requestContactsPermission() {
    if (!isContactsPermissionGranted()) {
      requestPermissions(Constants.CONTACTS_PERMISSION,
                         Constants.CONTACTS_PERMISSION_REQUEST_CODE);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == Constants.CONTACTS_PERMISSION_REQUEST_CODE) {
      if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
        return;
      }
      recreate();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode,
                                  @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == Constants.CONTACT_PICKER_REQUEST_CODE) {
      processContactPickerResult(resultCode, data);
    }
  }

  private void startContactPickerActivity() {
    Intent intent = new Intent(Intent.ACTION_PICK);
    intent.setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
    startActivityForResult(intent, Constants.CONTACT_PICKER_REQUEST_CODE);
  }

  private void processContactPickerResult(int resultCode,
                                          @Nullable Intent data) {
    if (resultCode != RESULT_OK || data == null) {
      return;
    }

    Uri contactUri = data.getData();
    if (contactUri == null) {
      return;
    }

    String[] projection = new String[]{
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY};
    Cursor cursor;
    try {
      cursor = getContentResolver().query(contactUri, projection, null, null, null);
    } catch (RuntimeException exception) {
      Toast.makeText(this, getString(R.string.number_missing), Toast.LENGTH_LONG).show();
      return;
    }

    if (cursor == null) {
      return;
    }

    if (cursor.moveToFirst()) {
      int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
      if (numberIndex != -1) {
        String number = cursor.getString(numberIndex);
        number = number.replaceAll("[^+0-9]", "");
        senderNumInput.setText(number);

        numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY);
        if (numberIndex != -1) {
          senderNameInput.setText(cursor.getString(numberIndex));
        }
      }
    }

    cursor.close();
  }
}
