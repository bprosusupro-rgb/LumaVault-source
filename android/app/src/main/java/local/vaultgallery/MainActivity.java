package local.vaultgallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private static final int PICK_TREE = 41;
  private static final int PICK_FILES = 42;
  private static final int SAVE_LOG = 43;
  private static final String IMAGE_HOST = "appassets.androidplatform.net";
  private static final String ORIGIN = "https://" + IMAGE_HOST;
  private static final String PAGE = ORIGIN + "/index.html";
  private static final Map<String, String> NO_STORE = Collections.singletonMap("Cache-Control", "no-store");
  private static final int THUMBNAIL_EDGE = 384;
  private static final String[] CHILD_COLUMNS = {
    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE
  };
  private static final String[] CHILD_METADATA = {
    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
    Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS
  };

  private static final class Photo {
    final Uri uri;
    final String mime;
    final boolean hasThumbnail;

    Photo(Uri uri, String mime, boolean hasThumbnail) {
      this.uri = uri;
      this.mime = mime;
      this.hasThumbnail = hasThumbnail;
    }
  }

  private static final class Selection {
    final CancellationSignal cancellation = new CancellationSignal();
    final Map<String, Photo> photos = new HashMap<>();
    final JSONArray items = new JSONArray();
    final int id;

    Selection(int id) { this.id = id; }

    void add(String name, String path, Uri uri, String mime, long time, long size, long flags) throws Exception {
      String key = id + "/" + items.length();
      String url = ORIGIN + "/photo/" + key;
      boolean thumbnail = (flags & Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
      JSONObject item = new JSONObject();
      item.put("name", name);
      item.put("path", path);
      item.put("uri", url);
      if (thumbnail || canDecodeThumbnail(mime)) item.put("thumbnail", url + "?thumbnail");
      item.put("mime", mime);
      item.put("lastModified", time);
      item.put("size", size);
      photos.put("/photo/" + key, new Photo(uri, mime, thumbnail));
      items.put(item);
    }
  }

  private interface SelectionReader {
    String read(Selection selection) throws Exception;
  }

  private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
  private final ExecutorService logWorker = Executors.newSingleThreadExecutor();
  private final DiagnosticLog diagnosticLog = new DiagnosticLog(300);
  private volatile Map<String, Photo> photos = Collections.emptyMap();
  private WebView webView;
  private Selection selection;
  private int selectionId;
  private boolean pageReady;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    diagnosticLog.add("Android app launched; API " + Build.VERSION.SDK_INT);
    webView = new WebView(this);
    webView.setBackgroundColor(0xff0c1016);
    FrameLayout safeArea = new FrameLayout(this);
    safeArea.addView(webView, new FrameLayout.LayoutParams(-1, -1));
    setContentView(safeArea);
    if (Build.VERSION.SDK_INT >= 30) {
      safeArea.setOnApplyWindowInsetsListener((view, insets) -> {
        android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        return WindowInsets.CONSUMED;
      });
    }
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    webView.addJavascriptInterface(new Bridge(), "AndroidGallery");
    webView.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView view, String url) {
        if (!PAGE.equals(url) || pageReady) return;
        pageReady = true;
        handleIncomingIntent();
      }

      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return !PAGE.equals(request.getUrl().toString());
      }

      @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return !PAGE.equals(url);
      }

      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (PAGE.equals(url.toString())) {
          try {
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK",
                Collections.singletonMap("Content-Security-Policy",
                    "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'"),
                getAssets().open("www/index.html"));
          } catch (IOException error) {
            return unavailable();
          }
        }
        if ("https".equals(url.getScheme()) && IMAGE_HOST.equals(url.getHost())) return readPhoto(url);
        return unavailable();
      }
    });
    webView.loadUrl(PAGE);
  }

  private WebResourceResponse readPhoto(Uri url) {
    Photo photo = photos.get(url.getPath());
    if (photo == null) return unavailable();
    if ("thumbnail".equals(url.getQuery())) {
      Bitmap bitmap = loadThumbnail(photo);
      if (bitmap != null) {
        try {
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          boolean alpha = bitmap.hasAlpha();
          Bitmap.CompressFormat format = alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
          if (bitmap.compress(format, 85, bytes)) {
            return imageResponse(alpha ? "image/png" : "image/jpeg", new ByteArrayInputStream(bytes.toByteArray()));
          }
        } finally {
          bitmap.recycle();
        }
      }
    }
    try {
      InputStream stream = getContentResolver().openInputStream(photo.uri);
      if (stream != null) return imageResponse(photo.mime, stream);
    } catch (Exception ignored) {
      // The underlying vault can be locked or unmounted while LumaVault is open.
    }
    return unavailable();
  }

  private static boolean canDecodeThumbnail(String mime) {
    if (Build.VERSION.SDK_INT < 28) return false;
    switch (mime) {
      case "image/jpeg":
      case "image/png":
      case "image/webp":
      case "image/avif":
      case "image/heic":
      case "image/heif":
      case "image/bmp":
        return true;
      default:
        return false;
    }
  }

  private Bitmap loadThumbnail(Photo photo) {
    if (photo.hasThumbnail) {
      try {
        Bitmap providerThumbnail = DocumentsContract.getDocumentThumbnail(getContentResolver(), photo.uri,
            new Point(THUMBNAIL_EDGE, THUMBNAIL_EDGE), null);
        if (providerThumbnail != null) return providerThumbnail;
      } catch (Exception ignored) {
        // Fall back to a local decode if the provider cannot supply a thumbnail.
      }
    }
    if (Build.VERSION.SDK_INT >= 28 && canDecodeThumbnail(photo.mime)) {
      try {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), photo.uri),
            (decoder, info, source) -> {
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              int width = info.getSize().getWidth(), height = info.getSize().getHeight();
              double scale = Math.min(1.0, THUMBNAIL_EDGE / (double) Math.max(width, height));
              decoder.setTargetSize(Math.max(1, (int) Math.round(width * scale)),
                  Math.max(1, (int) Math.round(height * scale)));
            });
      } catch (Exception ignored) {
        // The WebView can still try the original when the platform decoder does not support it.
      }
    }
    return null;
  }

  private static WebResourceResponse imageResponse(String mime, InputStream data) {
    return new WebResourceResponse(mime, null, 200, "OK", NO_STORE, data);
  }

  private static WebResourceResponse unavailable() {
    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
        Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
  }

  private static String extension(String path) {
    int dot = path.lastIndexOf('.');
    return dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
  }

  private static String imageMime(String name, String providerMime) {
    if (providerMime != null && providerMime.toLowerCase(Locale.ROOT).startsWith("image/")) return providerMime;
    String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name));
    return mime != null && mime.startsWith("image/") ? mime : null;
  }

  private static boolean isEdsUri(Uri uri) {
    String authority = uri == null ? null : uri.getAuthority();
    return authority != null && authority.toLowerCase(Locale.ROOT).contains("sovworks");
  }

  private void emit(String javascript) {
    runOnUiThread(() -> {
      if (webView != null) webView.evaluateJavascript(javascript, null);
    });
  }

  private void emitError(String message) {
    diagnosticLog.add("Could not read selection: " + message);
    emit("window.onNativeError(" + JSONObject.quote(message) + ")");
  }

  private static String detail(Exception error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private void startSelection(Uri source, SelectionReader reader) {
    if (selection != null) selection.cancellation.cancel();
    Selection next = new Selection(++selectionId);
    selection = next;
    emit("window.onNativeScanning()");
    fileWorker.execute(() -> {
      try {
        if (next.cancellation.isCanceled()) return;
        String warning = reader.read(next);
        if (next.cancellation.isCanceled()) return;
        String javascript = "window.onNativeFolder(" + JSONObject.quote(next.items.toString()) + ","
            + JSONObject.quote(isEdsUri(source) ? "eds" : "other") + "," + JSONObject.quote(warning) + ")";
        runOnUiThread(() -> {
          if (selection != next || webView == null) return;
          photos = Collections.unmodifiableMap(next.photos);
          emit(javascript);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (selection == next && webView != null) emitError(detail(error));
        });
      }
    });
  }

  private final class SafNode implements TreeImageScanner.Node {
    final Uri tree, uri;
    final String name, mime;
    final long time, size, flags;
    final CancellationSignal cancellation;
    final boolean root;

    SafNode(Uri tree, Uri uri, String name, String mime, long time, long size, long flags,
            CancellationSignal cancellation, boolean root) {
      this.tree = tree;
      this.uri = uri;
      this.name = name;
      this.mime = mime;
      this.time = time;
      this.size = size;
      this.flags = flags;
      this.cancellation = cancellation;
      this.root = root;
    }

    @Override public String name() { return name; }
    @Override public String key() { return uri.toString(); }
    @Override public boolean isDirectory() { return root || Document.MIME_TYPE_DIR.equals(mime); }
    @Override public boolean isFile() { return !isDirectory(); }
    @Override public boolean hasImageMime() { return imageMime(name == null ? "" : name, mime) != null; }

    @Override public TreeImageScanner.Node[] listFiles() throws IOException {
      Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri));
      try (Cursor cursor = query(children)) {
        if (cursor == null) throw new IOException("The document provider returned no response");
        int idColumn = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
        int nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
        int mimeColumn = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
        if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
          throw new IOException("The document provider omitted folder information");
        }
        int timeColumn = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED);
        int sizeColumn = cursor.getColumnIndex(Document.COLUMN_SIZE);
        int flagsColumn = cursor.getColumnIndex(Document.COLUMN_FLAGS);
        ArrayList<TreeImageScanner.Node> nodes = new ArrayList<>();
        while (cursor.moveToNext()) {
          cancellation.throwIfCanceled();
          String id = cursor.getString(idColumn);
          if (id == null) continue;
          Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
          nodes.add(new SafNode(tree, child, cursor.getString(nameColumn), cursor.getString(mimeColumn),
              number(cursor, timeColumn), number(cursor, sizeColumn), number(cursor, flagsColumn),
              cancellation, false));
        }
        return nodes.toArray(new TreeImageScanner.Node[0]);
      }
    }

    private Cursor query(Uri children) {
      try {
        return getContentResolver().query(children, CHILD_METADATA, null, null, null, cancellation);
      } catch (IllegalArgumentException unsupportedProjection) {
        return getContentResolver().query(children, CHILD_COLUMNS, null, null, null, cancellation);
      }
    }
  }

  private static long number(Cursor cursor, int column) {
    return column < 0 || cursor.isNull(column) ? 0 : cursor.getLong(column);
  }

  private String readFolder(Uri tree, Selection destination) throws Exception {
    Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    TreeImageScanner.Result result = TreeImageScanner.scan(new SafNode(tree, root, "Selected folder",
        Document.MIME_TYPE_DIR, 0, 0, 0, destination.cancellation, true));
    for (TreeImageScanner.Image image : result.images) {
      destination.cancellation.throwIfCanceled();
      SafNode node = (SafNode) image.node;
      String mime = imageMime(image.name, node.mime);
      destination.add(image.name, image.path, node.uri, mime == null ? "application/octet-stream" : mime,
          node.time, node.size, node.flags);
    }
    if (destination.items.length() == 0 && result.skippedFolders > 0) throw new IOException(result.warning());
    return result.warning();
  }

  private void addSingle(Uri uri, Selection destination) throws Exception {
    if (!"content".equals(uri.getScheme())) return;
    String name = uri.getLastPathSegment();
    long size = 0;
    long time = 0;
    long flags = 0;
    try (Cursor cursor = getContentResolver().query(uri, null, null, null, null, destination.cancellation)) {
      if (cursor != null && cursor.moveToFirst()) {
        int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
        size = number(cursor, cursor.getColumnIndex(OpenableColumns.SIZE));
        time = number(cursor, cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED));
        flags = number(cursor, cursor.getColumnIndex(Document.COLUMN_FLAGS));
      }
    } catch (Exception metadataUnavailable) {
      destination.cancellation.throwIfCanceled();
    }
    if (name == null || name.isEmpty()) name = "Photo";
    String mime = imageMime(name, getContentResolver().getType(uri));
    if (mime != null) destination.add(name, name, uri, mime, time, size, flags);
  }

  private void readPictures(Intent intent) {
    LinkedHashSet<Uri> uris = new LinkedHashSet<>();
    ClipData clip = intent.getClipData();
    if (clip != null) {
      for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
    }
    if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
      ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (streams != null) uris.addAll(streams);
    } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
      uris.add(intent.getParcelableExtra(Intent.EXTRA_STREAM));
    } else {
      uris.add(intent.getData());
    }
    uris.remove(null);
    if (uris.isEmpty()) {
      emitError("Android did not return any selected photos.");
      return;
    }
    startSelection(uris.iterator().next(), destination -> {
      for (Uri uri : uris) {
        destination.cancellation.throwIfCanceled();
        try {
          addSingle(uri, destination);
        } catch (Exception unreadable) {
          diagnosticLog.add("An individual photo could not be read: " + unreadable.getClass().getSimpleName());
        }
      }
      if (destination.items.length() == 0) throw new IOException("No readable photos were selected.");
      int skipped = uris.size() - destination.items.length();
      return skipped == 0 ? "" : skipped + " selected photo(s) could not be read.";
    });
  }

  private void handleIncomingIntent() {
    Intent intent = getIntent();
    if (intent == null) return;
    String action = intent.getAction();
    if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
    setIntent(new Intent(Intent.ACTION_MAIN));
    try {
      readPictures(intent);
    } catch (RuntimeException error) {
      emitError("Android did not return a usable image selection.");
    }
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (pageReady) handleIncomingIntent();
  }

  @Override public void onBackPressed() {
    if (webView == null || !pageReady) { super.onBackPressed(); return; }
    webView.evaluateJavascript("window.closeGalleryDialog()", closed -> {
      if (!"true".equals(closed)) super.onBackPressed();
    });
  }

  @Override protected void onDestroy() {
    if (selection != null) selection.cancellation.cancel();
    photos = Collections.emptyMap();
    fileWorker.shutdownNow();
    logWorker.shutdown();
    if (webView != null) {
      ((android.view.ViewGroup) webView.getParent()).removeView(webView);
      webView.destroy();
      webView = null;
    }
    super.onDestroy();
  }

  private class Bridge {
    @JavascriptInterface public String getDiagnosticLog() { return diagnosticLog.snapshot(); }
    @JavascriptInterface public void logEvent(String message) { diagnosticLog.add(message); }
    @JavascriptInterface public void clearDiagnosticLog() { diagnosticLog.clear(); diagnosticLog.add("Log cleared by user"); }
    @JavascriptInterface public void clearSelection() {
      runOnUiThread(() -> {
        if (selection != null) selection.cancellation.cancel();
        selection = null;
        photos = Collections.emptyMap();
      });
    }

    @JavascriptInterface public void exportDiagnosticLog() {
      runOnUiThread(() -> {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "LumaVault-diagnostics.txt");
        launchPicker(intent, SAVE_LOG);
      });
    }

    @JavascriptInterface public String edsStatus() {
      String[] authorities = {"com.sovworks.eds.android.providers.documents.lite", "com.sovworks.eds.android.providers.documents"};
      for (String authority : authorities) {
        if (getPackageManager().resolveContentProvider(authority, 0) != null) return "provider";
      }
      String[] packages = {"com.sovworks.edslite", "com.sovworks.eds.android", "com.sovworks.projecteds"};
      for (String packageName : packages) {
        try { getPackageManager().getPackageInfo(packageName, 0); return "installed"; } catch (Exception ignored) {}
      }
      return "missing";
    }

    @JavascriptInterface public void pickFolder() {
      runOnUiThread(() -> {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        launchPicker(intent, PICK_TREE);
      });
    }

    @JavascriptInterface public void pickPictures() {
      runOnUiThread(() -> {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        launchPicker(intent, PICK_FILES);
      });
    }
  }

  private void launchPicker(Intent intent, int request) {
    try {
      startActivityForResult(intent, request);
    } catch (RuntimeException error) {
      if (request == SAVE_LOG) emit("window.onDiagnosticExported(false," + JSONObject.quote(detail(error)) + ")");
      else emitError("Android could not open the system picker.");
    }
  }

  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == SAVE_LOG) {
      if (result == RESULT_OK && data != null && data.getData() != null) saveLog(data.getData());
      else emit("window.onDiagnosticExported(false, 'Save canceled.')");
      return;
    }
    if (result != RESULT_OK || data == null) return;
    try {
      if (request == PICK_TREE) {
        Uri tree = data.getData();
        if (tree == null) throw new IOException("Android did not return a folder.");
        startSelection(tree, destination -> readFolder(tree, destination));
      } else if (request == PICK_FILES) {
        readPictures(data);
      }
    } catch (Exception error) {
      emitError(detail(error));
    }
  }

  private void saveLog(Uri destination) {
    logWorker.execute(() -> {
      try (OutputStream stream = getContentResolver().openOutputStream(destination, "wt")) {
        if (stream == null) throw new IOException("Android returned no writable output stream");
        stream.write(diagnosticLog.snapshot().getBytes(StandardCharsets.UTF_8));
      } catch (Exception error) {
        emit("window.onDiagnosticExported(false," + JSONObject.quote(detail(error)) + ")");
        return;
      }
      emit("window.onDiagnosticExported(true, '')");
    });
  }
}
