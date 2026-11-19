# LumaVault

`index.html` is a single-file, offline photo gallery. It scans a selected Android folder recursively and builds a browsable album tree; inside the Android app it can also receive image files shared/opened from EDS Lite.

## Android / EDS Lite workflow

1. If the VeraCrypt filesystem is exFAT, install EDS Lite's exFAT module, then unlock/open the container in EDS Lite.
2. In LumaVault, tap **Choose a folder**. EDS Lite exposes only currently open containers as picker roots, and labels each root with the container's own title. Unlock/open the container first, then select that name or a picture folder beneath it. EDS Lite's Lite source includes the registered `DocumentsProvider`, so SAF tree access is the full-gallery path.
3. LumaVault scans nested folders, builds the album tree, and displays matching image files. If EDS is not listed in the picker on your device, use EDS Lite's **Share** or **Open with** action on pictures and select LumaVault instead.
4. Close LumaVault, then close/unmount the container in EDS Lite when finished.

The app does not implement VeraCrypt or decrypt containers. EDS Lite remains the container layer and its official project describes a separate exFAT module. LumaVault reads only content URIs explicitly granted by Android and processes pictures locally.

The app does not decrypt the container itself. LumaVault reads only the folder URI Android grants after the user chooses it, recursively through EDS's SAF provider. If a specific installed EDS build or Android picker does not expose the opened volume, image sharing remains the fallback. Do not export/decrypt the whole vault just to use the gallery. This Android app packages the HTML in a local WebView so a separate web server is not required.
