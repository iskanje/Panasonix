# Panasonix

Native Android app for everyday control of a Panasonic CS-HZ35ZKE air-to-air heat pump through Panasonic Comfort Cloud.

The interface uses only standard Jetpack Compose Material 3 components and the default Material 3 theme. It keeps operating mode, target temperature, fan speed, power, and status visible without nested control menus.

## Current state

- OAuth2/PKCE sign-in through Panasonic's hosted browser page
- MFA handled by Panasonic's sign-in page
- Access and refresh tokens encrypted with an Android Keystore key
- Automatic token refresh
- Comfort Cloud device discovery, preferring an HZ35 device when present
- Immediate startup from the last confirmed status with background refresh
- Cached device discovery and pooled HTTP connections for faster commands
- Heat, Fan, Cool, Dry, and Auto mode selection
- Fan speed selection: Auto and levels 1–5
- Vertical and horizontal airflow: Auto, Swing, and five fixed positions
- Temperature control from 16–30 °C
- Power control
- Pending and confirmed command states
- Explicit connection and API errors
- Unit-tested API value mapping, command generation, temperature limits, and request signing

The app does not store or receive the Panasonic account password. Authentication occurs on Panasonic's page and returns OAuth tokens to the app.

## Open in Android Studio

1. Open this folder as an existing project.
2. Allow Gradle sync to complete.
3. Run the `app` configuration on an emulator or Android device.
4. Tap **Continue to Panasonic** and finish signing in in the browser.
5. If Android asks which app should open the callback, choose **Panasonix**.

The heat pump must already be registered using the official Panasonic Comfort Cloud app.

## Important limitation

Panasonic does not publish or support this consumer control API. The integration follows the current Comfort Cloud app protocol and can break when Panasonic changes it. Version `0.4.5` is compiled and unit-tested, but live authentication and commands still require end-to-end testing with a real Comfort Cloud account and CS-HZ35ZKE.

If Comfort Cloud returns agreement error `4103`, review the changed terms or privacy notice in Panasonic's official app. Panasonix will not accept agreements automatically.

## Branding

This is an independent, unofficial project. It intentionally avoids Panasonic logos and does not present itself as an official Panasonic application.

## Protocol reference

The current Comfort Cloud protocol behavior was cross-checked against the MIT-licensed `aio-panasonic-comfort-cloud` project, release `2026.8.6`. Its license is retained in `third_party/aio-panasonic-comfort-cloud-LICENSE.txt`.
