# LumaVault source

This folder is the complete editable source for the LumaVault Android gallery.

- `gallery/` contains the standalone HTML photo gallery and its usage notes.
- `android/` contains the Android WebView wrapper, image share/open integration, Gradle configuration, and build script.

To run the dependency-free gallery behavior check, open a terminal in `gallery/` and run `node smoke-test.js`.

See [Android build instructions](android/README.md) for the Gradle wrapper, release build, signing, and Windows debug helper.

## How the EDS connection works

`gallery/index.html` is the photo-gallery UI. In a regular browser it can browse folders exposed by that browser, but browser-only HTML cannot directly request Android Storage Access Framework (SAF) permissions. The Android wrapper provides that bridge: it launches Android's folder-tree picker, recursively reads the selected SAF tree, and passes image content URIs to the HTML gallery. It does not decrypt or mount VeraCrypt containers itself.

EDS Lite's source implements an Android `DocumentsProvider` with root queries, child-document queries, and document-stream reads. This is the API LumaVault uses. Unlock/open the container in EDS Lite first; its root is exposed while open, and may appear under the container's own title rather than the words “EDS Lite.” Select the container or its picture folder in **Choose a photo folder**. The gallery scans nested folders for supported image files and builds the album navigation from their paths.

For exFAT-formatted containers, EDS Lite needs its separate exFAT filesystem module installed. LumaVault consumes files EDS Lite exposes after unlocking; it does not replace that module or EDS's decryption. No root access is required for the SAF-based flow. If the Android picker does not expose the EDS location on a particular device/build, share or open individual images from EDS Lite as a fallback.

Full EDS/SAF compatibility still needs an on-device test with the installed EDS Lite version and a real unlocked container; a successful APK build alone cannot prove that device-specific flow.
