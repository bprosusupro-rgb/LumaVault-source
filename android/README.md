# LumaVault for Android

The Android wrapper packages the single-file gallery from `../gallery/index.html`. The Storage Access Framework provides access to the folder you choose. LumaVault also accepts images through Android’s Open with and Share actions. Unlock your VeraCrypt container in EDS Lite before selecting it by name in Android’s folder picker.

## Build

Use JDK 17 or 21 and Android SDK Platform 36 with Build Tools 35.0.0. Set `ANDROID_HOME` to your Android SDK directory. From this directory, run:

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

Run `gradlew.bat` on Windows. The release build is written to `app/build/outputs/apk/release/app-release-unsigned.apk`. To produce an installable release, use Android Build Tools to align the APK and sign it with your release keystore:

```sh
zipalign -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk LumaVault-aligned.apk
apksigner sign --ks /path/to/release.jks --out LumaVault-release.apk LumaVault-aligned.apk
apksigner verify --verbose LumaVault-release.apk
```

To update an existing installation, use the same signing key as that installation. The Windows helper `build.ps1` still builds the debug variant and copies it to the `apk-vr` folder on the checkout’s drive.

## Verify

Run `node ../gallery/smoke-test.js` for gallery behavior. For an Android device test, select a local photo folder, open a photo, use Back to close the viewer, and share an image to LumaVault. Repeat with the unlocked EDS Lite container to confirm that the installed provider exposes the tree and reads its images.
