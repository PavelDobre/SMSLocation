# SMSLocation

SMSLocation is a small Android utility that reacts to specially formatted SMS messages from trusted phone numbers.

Its main purpose is to help locate or physically find a phone when internet-based tools are unavailable or unreliable.

## Features

### Location request

Each trusted number can have its own location request code.

When SMSLocation receives that code, it tries to determine the device location using the enabled providers:

* Google / Fused Location Services
* GPS
* Network Location
* Last known locations

Google Location Services are preferred when multiple providers are enabled.

The app then sends an SMS reply containing information such as:

* Google Maps link
* coordinates
* accuracy
* speed
* timestamp

Long replies may be sent as multipart SMS.

### Remote alarm

A separate RING code can be configured.

When received from an authorized number, the app raises the alarm volume and plays a loud repeating sound.

The alarm can be stopped:

* from the notification
* automatically after the timeout
* remotely with:

```text
<RING_CODE> STOP
```

## Trusted numbers

Commands are accepted only from configured phone numbers.

Each number can have separate codes for:

* Location
* RING

The main screen can also send these commands by SMS to another phone running SMSLocation.

## Permissions

The app requires sensitive permissions because they are part of its core functionality:

* Receive SMS
* Send SMS
* Location
* Background location
* Notifications
* Modify audio settings

Recent Android versions may require additional confirmation for SMS and background-location permissions, especially when the APK is installed manually.

## Limitations

SMSLocation depends on Android, SMS delivery, GPS availability, Google Play Services, permissions, and device-specific background restrictions.

Location requests or alarms may therefore fail or behave differently on some devices or Android versions.

## Disclaimer

This is a small personal project built for practical use and experimentation.

It is not a professionally engineered, audited, or supported tracking or security product. Bugs, compatibility issues, security weaknesses, and unexpected failures are possible.

**Do not rely on SMSLocation for emergencies, personal safety, theft prevention, medical situations, or other critical use cases.**

Use it at your own risk.

## Credits and licence

SMSLocation inspired by [anevero/sms_my_gps](https://github.com/anevero/sms_my_gps)

Licensed under the GNU General Public License, version 2 — see `LICENSE.txt`.
