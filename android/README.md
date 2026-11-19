# LumaVault Android app

This Android WebView app packages the gallery from `../gallery/index.html`. It uses Android's folder-tree picker to recursively scan SAF-provided folders and also accepts individual or multiple images through Android `VIEW`, `SEND`, and `SEND_MULTIPLE` intents. EDS Lite's Lite source registers a `DocumentsProvider` whose roots are only the currently open containers and whose root titles are the container titles; unlock first, then select the container by its own name. It does not implement VeraCrypt decryption or require root.

Build from PowerShell in this directory with `./build.ps1`. The script takes the gallery HTML from the sibling `gallery` folder, builds the APK locally, and copies only the finished APK to `F:\apk-vr\LumaVault-debug.apk`.

If the EDS location is not listed by the system folder picker on a particular device/build, open or select pictures in EDS Lite and use **Share** or **Open with → LumaVault**. Multi-picture handoff depends on EDS Lite offering multiple selection/sharing. Validate folder scanning on-device before assuming the EDS provider is available.
