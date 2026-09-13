package uk.sensoryunderload.Location.events;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import uk.sensoryunderload.Location.data.Preferences;

public final class GPSSender {
  private static final String TAG = "GPSSender";
  private static int minimumLocationAccuracy = 100;
  private enum ResultType {
    GPS {
      @NonNull
      @Override
      public String toString() {
        return "GPS, current";
      }
    },
    GOOGLE {
      @NonNull
      @Override
      public String toString() {
        return "Google Location, current";
      }
    },
    NETWORK {
      @NonNull
      @Override
      public String toString() {
        return "Network, current";
      }
    },
    GPS_LAST_KNOWN {
      @NonNull
      @Override
      public String toString() {
        return "GPS, Last Known";
      }
      @Override
      public boolean isLastKnown() { return true; }
    },
    GOOGLE_LAST_KNOWN {
      @NonNull
      @Override
      public String toString() {
        return "Google Location, Last Known";
      }
      @Override
      public boolean isLastKnown() { return true; }
    },
    NETWORK_LAST_KNOWN {
      @NonNull
      @Override
      public String toString() {
        return "Network, Last Known";
      }
      @Override
      public boolean isLastKnown() { return true; }
    };

    public boolean isLastKnown() { return false; }
  }
  private static class Result {
    public boolean pending;
    public Location location;
    public ResultType type;

    public Result(int typeIndex) {
      this.pending = true;
      this.location = null;
      this.type = ResultType.values()[typeIndex];
    }

    @NonNull
    @Override
    public String toString() {
      String returnString = "";

      if (this.location != null) {
        returnString = this.type.toString();

        if (this.type.isLastKnown()) {
          long millis = Calendar.getInstance().getTimeInMillis() - getLocationAgeMillis(this.location);
          Date date = new Date(millis);
          returnString += " " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date);
        }
      }

      return returnString;
    }
  }

  // This field only ever stores getApplicationContext() and is cleared in reset().
  @SuppressLint("StaticFieldLeak")
  private static Context context = null;
  private static Result[] results = null;
  private static final ArrayList<String> recipients = new ArrayList<>();
  private static final ArrayList<LocationListener> locationListeners = new ArrayList<>();
  private static LocationCallback fusedLocationCallback = null;
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private static Runnable timeoutRunnable = null;
  private static Runnable systemProvidersRunnable = null;
  private static final long GOOGLE_HEAD_START_MS = 10000;
  private static final long GLOBAL_TIMEOUT_MS = 90000;

  // Following a call of the following method all location requests will
  // be triggered (with disabled ones failing immediately). Any fail or
  // success will update the "pending" member of it's result and call
  // notifyListener to establish whether the whole process is complete,
  // with any successful result inserted into results. After a global
  // timeout all results' pending members are set to false and
  // notifyResults will be called.
  private static long getLocationAgeMillis(Location location) {
    long ageNanos = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
    if (ageNanos < 0) {
      return 0;
    }
    return TimeUnit.NANOSECONDS.toMillis(ageNanos);
  }

  @SuppressLint("MissingPermission")
  public static void notify(Context _context, String recipient) {
    final boolean newRequest = recipients.isEmpty();

    recipients.add (recipient);

    if (newRequest) {
      results = new Result[6];
      for (int i = 0; i < ResultType.values().length; ++i) {
        results[i] = new Result(i);
      }
      context = _context.getApplicationContext();
      minimumLocationAccuracy = Preferences.getLocationAccuracy(context);
      int maximumAttemptsNumber = Preferences.getAttemptsNumber(context);

      try {
        notifyFusedProvider(maximumAttemptsNumber);
      } catch (SecurityException exception) {
        Log.e(TAG, "Fused location access denied", exception);
        results[ResultType.GOOGLE.ordinal()].pending = false;
        results[ResultType.GOOGLE_LAST_KNOWN.ordinal()].pending = false;
      } catch (RuntimeException exception) {
        Log.e(TAG, "Unable to start fused location request", exception);
        results[ResultType.GOOGLE.ordinal()].pending = false;
        results[ResultType.GOOGLE_LAST_KNOWN.ordinal()].pending = false;
      }

      // Give Google/Fused Location a short head start when it is selected.
      // GPS and Network still run as fallbacks, but they do not start at the
      // exact same time as Google anymore.
      boolean giveGoogleHeadStart =
              Preferences.isFusedLocationEnabled(context) &&
              Preferences.areGooglePlayServicesAvailable(context);

      systemProvidersRunnable = () -> {
        if (context == null || recipients.isEmpty()) {
          return;
        }
        try {
          notifySystemProvider(LocationManager.GPS_PROVIDER,
                               maximumAttemptsNumber);
          notifySystemProvider(LocationManager.NETWORK_PROVIDER,
                               maximumAttemptsNumber);
        } catch (SecurityException exception) {
          Log.e(TAG, "System location provider access denied", exception);
          markSystemProvidersFailed();
          notifyResults();
        } catch (RuntimeException exception) {
          Log.e(TAG, "System location provider failed", exception);
          markSystemProvidersFailed();
          notifyResults();
        } finally {
          systemProvidersRunnable = null;
        }
      };

      if (giveGoogleHeadStart) {
        handler.postDelayed(systemProvidersRunnable, GOOGLE_HEAD_START_MS);
      } else {
        systemProvidersRunnable.run();
      }

      timeoutRunnable = () -> {
        if (results == null || recipients.isEmpty()) {
          return;
        }
        for (Result result : results) {
          result.pending = false;
        }
        timeoutRunnable = null;
        notifyResults();
      };
      handler.postDelayed(timeoutRunnable, GLOBAL_TIMEOUT_MS);
    }
  }

  // This method collates all of the results and determines which, if
  // any, should be sent.
  //
  // Each live result (i.e. not "LastKnown") is checked. If any have
  // succeeded and are successful (i.e. accurate enough) then that
  // result is sent.
  //
  // If no live result is successful but some are still pending then
  // nothing is done.
  //
  // If no live result is successful and none are pending then the most
  // accurate complete live result is sent.
  //
  // If no live result is pending or exists then the most *recent*
  // last-known result is sent.
  public static void notifyResults() {
    if (!recipients.isEmpty()) {
      boolean liveResultsPending = false;
      // Check whether any live results have completed.
      for (Result result : results) {
        if (!result.type.isLastKnown()) {
          if (result.pending) {
            liveResultsPending = true;
          } else {
            if ((result.location != null) &&
                (result.location.getAccuracy() < minimumLocationAccuracy)) {
              // Send this result
              for (String recipient : recipients) {
                (new SMSSender(context, recipient, result.location, result.toString())).sendMessage();
              }
              reset();
              return;
            }
          }
        }
      }

      if (!liveResultsPending) {
        // No live result accurate enough. Use the most accurate live
        // result that exists.
        Result bestResult = null;
        for (Result result : results) {
          if (!result.type.isLastKnown() &&
              (result.location != null)) {
            if ((bestResult == null) ||
                (result.location.getAccuracy() < bestResult.location.getAccuracy())) {
              bestResult = result;
            }
          }
        }

        // No live result exists.

        // Ensure no "last-known" results are pending.
        for (Result result : results) {
          if (result.type.isLastKnown() &&
              result.pending) {
            return;
          }
        }

        // Use the most-recent "last-known" result.
        if (bestResult == null) {
          for (Result result : results) {
            if (result.type.isLastKnown() &&
                !result.pending &&
                (result.location != null)) {
              if ((bestResult == null) ||
                  (getLocationAgeMillis(result.location) < getLocationAgeMillis(bestResult.location))) {
                bestResult = result;
              }
            }
          }
        }

        Location location = null;
        String bestResultProvider = "Location Unknown";
        if (bestResult != null) {
          location = bestResult.location;
          bestResultProvider = bestResult.toString();
        }

        for (String recipient : recipients) {
          (new SMSSender(context, recipient, location, bestResultProvider)).sendMessage();
        }
        reset();
      }
    }
  }

  // Resets the "recipients" array, along with any pending location
  // requests.
  private static void reset() {
    if (timeoutRunnable != null) {
      handler.removeCallbacks(timeoutRunnable);
      timeoutRunnable = null;
    }
    if (systemProvidersRunnable != null) {
      handler.removeCallbacks(systemProvidersRunnable);
      systemProvidersRunnable = null;
    }

    if (context != null) {
      LocationManager systemLocationProvider = (LocationManager) context
              .getSystemService(Context.LOCATION_SERVICE);
      if (systemLocationProvider != null) {
        for (LocationListener listener : locationListeners) {
          try {
            systemLocationProvider.removeUpdates(listener);
          } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to remove system location listener", exception);
          }
        }
      }
      if (fusedLocationCallback != null) {
        try {
          FusedLocationProviderClient fusedLocationProvider =
                  LocationServices.getFusedLocationProviderClient(context);
          fusedLocationProvider.removeLocationUpdates(fusedLocationCallback);
        } catch (RuntimeException exception) {
          Log.w(TAG, "Unable to remove fused location listener", exception);
        }
      }
    }

    locationListeners.clear();
    fusedLocationCallback = null;

    recipients.clear();
    context = null;
  }

  private static void markSystemProvidersFailed() {
    if (results == null) {
      return;
    }
    results[ResultType.GPS.ordinal()].pending = false;
    results[ResultType.GPS_LAST_KNOWN.ordinal()].pending = false;
    results[ResultType.NETWORK.ordinal()].pending = false;
    results[ResultType.NETWORK_LAST_KNOWN.ordinal()].pending = false;
  }

  @SuppressLint("MissingPermission")
  private static void notifyFusedProvider(int maximumAttemptsNumber) {
    FusedLocationProviderClient fusedLocationProvider =
            (Preferences.areGooglePlayServicesAvailable(context)) ?
            LocationServices.getFusedLocationProviderClient(context) : null;

    Result result = results[ResultType.GOOGLE.ordinal()];
    Result lastKnownResult = results[ResultType.GOOGLE_LAST_KNOWN.ordinal()];
    if (fusedLocationProvider == null) {
      result.pending = false;
      lastKnownResult.pending = false;
      return;
    }

    if (Preferences.isFusedLocationEnabled(context)) {
      LocationRequest locationRequest = LocationRequest.create()
              .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
              .setInterval(5000);
      fusedLocationCallback = new FusedLocationCallback(
              fusedLocationProvider, maximumAttemptsNumber);
      fusedLocationProvider.requestLocationUpdates(
              locationRequest, fusedLocationCallback, Looper.getMainLooper())
              .addOnFailureListener(exception -> {
                Log.e(TAG, "Fused current-location request failed", exception);
                if (results != null) {
                  results[ResultType.GOOGLE.ordinal()].pending = false;
                  notifyResults();
                }
              });
    } else {
      result.pending = false;
    }

    if (Preferences.isFusedLastKnownEnabled(context)) {
      OnCompleteListener<Location> listener =
              new FusedOnCompleteListener();
      fusedLocationProvider.getLastLocation().addOnCompleteListener(listener);
    } else {
      lastKnownResult.pending = false;
    }
  }

  @SuppressLint("MissingPermission")
  private static void notifySystemProvider(String provider, int maximumAttemptsNumber) {
    LocationManager systemLocationProvider = (LocationManager) context
            .getSystemService(Context.LOCATION_SERVICE);

    Result result = results[ResultType.GPS.ordinal()];
    Result lastKnownResult = results[ResultType.GPS_LAST_KNOWN.ordinal()];
    if (!LocationManager.GPS_PROVIDER.equals(provider)) {
      result = results[ResultType.NETWORK.ordinal()];
      lastKnownResult = results[ResultType.NETWORK_LAST_KNOWN.ordinal()];
    }

    if (!systemLocationProvider.isProviderEnabled(provider)) {
      Log.i(TAG, "Provider '" + provider + "' not enabled");
      result.pending = false;
      lastKnownResult.pending = false;
      notifyResults();
      return;
    }

    if (Preferences.isSystemProviderEnabled(context, provider)) {
      LocationListener listener = new SystemGpsLocationListener(
              systemLocationProvider, maximumAttemptsNumber, result);
      systemLocationProvider.requestLocationUpdates(
              provider, 5000, 0, listener);
      locationListeners.add(listener);
    } else {
      result.pending = false;
    }

    if (Preferences.isSystemProviderLastKnownEnabled(context, provider)) {
      Location location = systemLocationProvider.getLastKnownLocation(
              provider);
      Log.i(TAG, "Received last known location from system provider");
      if (location != null) {
        Log.i(TAG, "Recording last known location from system provider");
        lastKnownResult.location = location;
      }
    }
    lastKnownResult.pending = false;
    notifyResults();
  }

  // Location callback implementation for requesting updates from fused
  // location provider.
  private static class FusedLocationCallback extends LocationCallback {
    final private FusedLocationProviderClient provider;
    final private int maximumAttemptsNumber;
    private int currentAttemptsNumber;

    public FusedLocationCallback(FusedLocationProviderClient provider,
                                 int maximumAttemptsNumber) {
      this.provider = provider;
      this.maximumAttemptsNumber = maximumAttemptsNumber;
      this.currentAttemptsNumber = 0;
    }

    @Override
    public void onLocationAvailability(
        @NonNull LocationAvailability locationAvailability) {
    }

    @Override
    public void onLocationResult(LocationResult result) {
      ++currentAttemptsNumber;
      Log.i(TAG, "Received current location from fused provider, attempt " +
                 currentAttemptsNumber);

      List<Location> locations = result.getLocations();
      if (locations.isEmpty()) {
        Log.i(TAG, "Skipping location from fused provider: no locations returned");
      } else {
        Location location = locations.get(locations.size() - 1);
        results[ResultType.GOOGLE.ordinal()].location = location;

        if (location.getAccuracy() > minimumLocationAccuracy) {
          Log.i(TAG, "Skipping location from fused provider: accuracy is " +
                     location.getAccuracy());
        } else {
          results[ResultType.GOOGLE.ordinal()].pending = false;
          Log.i(TAG, "Recording current location from fused provider");
          provider.removeLocationUpdates(this);
          notifyResults();
        }
      }

      if (currentAttemptsNumber >= maximumAttemptsNumber) {
        results[ResultType.GOOGLE.ordinal()].pending = false;
        provider.removeLocationUpdates(this);
        notifyResults();
      }
    }
  }

  // OnComplete listener for sending last known location received from fused
  // location provider.
  private static final class FusedOnCompleteListener
          implements OnCompleteListener<Location> {

    public FusedOnCompleteListener() {}

    @Override
    public void onComplete(@NonNull Task<Location> task) {
      Log.i(TAG, "Received last known location from fused provider");
      if (results == null) {
        return;
      }

      if (task.isSuccessful()) {
        Location location = task.getResult();
        if (location != null) {
          Log.i(TAG, "Recording last known location from fused provider");
          results[ResultType.GOOGLE_LAST_KNOWN.ordinal()].location = location;
        }
      } else {
        Log.e(TAG, "Fused last-known location request failed", task.getException());
      }

      results[ResultType.GOOGLE_LAST_KNOWN.ordinal()].pending = false;
      notifyResults();
    }
  }

  // Location listener implementation for requesting updates from system
  // GPS location provider.
  private static class SystemGpsLocationListener implements LocationListener {
    private final LocationManager provider;
    private final int maximumAttemptsNumber;
    private int currentAttemptsNumber;
    private final Result result;

    public SystemGpsLocationListener(LocationManager provider,
                                     int maximumAttemptsNumber,
                                     Result result) {
      this.provider = provider;
      this.maximumAttemptsNumber = maximumAttemptsNumber;
      this.currentAttemptsNumber = 0;
      this.result = result;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
      ++currentAttemptsNumber;
      Log.i(TAG, "Received current location from system provider, attempt " +
                 currentAttemptsNumber);

      if (currentAttemptsNumber < maximumAttemptsNumber) {
        result.location = location;

        if (location.getAccuracy() > minimumLocationAccuracy) {
          Log.i(TAG, "Skipping location from system provider: accuracy is " +
                     location.getAccuracy());
        } else {
          Log.i(TAG, "Recording current location from system provider");
          provider.removeUpdates(this);
          result.pending = false;
          notifyResults();
        }
      }

      if (currentAttemptsNumber >= maximumAttemptsNumber) {
        provider.removeUpdates(this);
        result.pending = false;
        notifyResults();
      }
    }
  }
}
