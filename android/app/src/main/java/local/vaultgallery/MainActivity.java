package local.vaultgallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.documentfile.provider.DocumentFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
  private static final int PICK_TREE = 41;
  private static final int PICK_FILES = 42;
  private static final int SAVE_LOG = 43;
  private WebView webView;
  private final Map<String, Uri> imageUris = new ConcurrentHashMap<>();
  private final DiagnosticLog diagnosticLog = new DiagnosticLog(300);

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    diagnosticLog.add("Android app launched; API " + android.os.Build.VERSION.SDK_INT);
    webView = new WebView(this); setContentView(webView);
    WebSettings settings = webView.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
    webView.addJavascriptInterface(new Bridge(), "AndroidGallery");
    webView.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        handleIncomingIntent(getIntent());
      }

      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri requested = request.getUrl();
        if (!"native".equals(requested.getScheme())) return super.shouldInterceptRequest(view, request);
        try {
          Uri target = imageUris.get(requested.getHost());
          if (target == null) return null;
          InputStream stream = getContentResolver().openInputStream(target);
          if (stream == null) return null;
          String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(requested.getPath()));
          return new WebResourceResponse(mime == null ? "application/octet-stream" : mime, null, stream);
        } catch (Exception ignored) { return null; }
      }
    });
    webView.loadUrl("file:///android_asset/www/index.html");
  }

  private String extension(String path) { int dot = path == null ? -1 : path.lastIndexOf('.'); return dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.US) : ""; }
  private boolean isImage(String name) { return name != null && extension(name).matches("jpg|jpeg|png|gif|webp|avif|bmp|heic|heif|svg"); }
  private boolean isEdsUri(Uri uri) { String authority = uri == null ? null : uri.getAuthority(); return authority != null && authority.toLowerCase(Locale.US).contains("sovworks"); }
  private void emit(String javascript) { runOnUiThread(() -> webView.evaluateJavascript(javascript, null)); }
  private void emitError(String message) { emit("window.onNativeError(" + JSONObject.quote(message) + ")"); }

  private final class SafNode implements TreeImageScanner.Node {
    final DocumentFile document;
    final Uri treeUri;
    final String displayName;
    final String mimeType;
    final boolean root;
    SafNode(DocumentFile document, Uri treeUri, boolean root) {
      this(document, treeUri, root, document.getName(), root ? DocumentsContract.Document.MIME_TYPE_DIR : document.getType());
    }
    SafNode(DocumentFile document, Uri treeUri, boolean root, String displayName, String mimeType) {
      this.document = document;
      this.treeUri = treeUri;
      this.root = root;
      this.displayName = displayName;
      this.mimeType = mimeType;
    }
    @Override public String name() { return displayName; }
    @Override public String key() { return document.getUri().toString(); }
    @Override public boolean isDirectory() { return root || DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType); }
    @Override public boolean isFile() { return !root && mimeType != null && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType); }
    @Override public TreeImageScanner.Node[] listFiles() throws Exception {
      Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(document.getUri()));
      String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
      Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null);
      if (cursor == null) throw new IOException("The document provider returned no response for folder '" + (displayName == null ? "Unnamed" : displayName) + "'");
      java.util.ArrayList<TreeImageScanner.Node> nodes = new java.util.ArrayList<>();
      try {
        int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        int typeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
        if (idColumn < 0 || nameColumn < 0 || typeColumn < 0) throw new IOException("The document provider omitted folder information for '" + (displayName == null ? "Unnamed" : displayName) + "'");
        while (cursor.moveToNext()) {
          String documentId = cursor.getString(idColumn);
          if (documentId == null) continue;
          String childName = cursor.getString(nameColumn);
          String childMime = cursor.getString(typeColumn);
          Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
          DocumentFile child = DocumentFile.fromSingleUri(MainActivity.this, childUri);
          if (child == null) throw new IOException("Could not open document entry '" + (childName == null ? documentId : childName) + "'");
          nodes.add(new SafNode(child, treeUri, false, childName, childMime));
        }
      } finally {
        cursor.close();
      }
      return nodes.toArray(new TreeImageScanner.Node[0]);
    }
  }

  private JSONArray photoJson(TreeImageScanner.Result scan) {
    JSONArray output = new JSONArray();
    for (TreeImageScanner.Image image : scan.images) {
      SafNode node = (SafNode) image.node;
      Uri uri = node.document.getUri();
      try {
        String id = "img" + imageUris.size();
        JSONObject item = new JSONObject();
        item.put("name", image.name);
        item.put("path", image.path);
        item.put("uri", "native://" + id + "/" + image.name);
        item.put("lastModified", node.document.lastModified());
        item.put("size", node.document.length());
        item.put("mime", getContentResolver().getType(uri));
        imageUris.put(id, uri);
        output.put(item);
      } catch (Exception error) {
        scan.skip(image.path, error);
      }
    }
    return output;
  }

  private void addSingle(Uri uri, String name, JSONArray output) throws Exception {
    if (uri == null) return;
    String mime = getContentResolver().getType(uri);
    if (!isImage(name) && (mime == null || !mime.toLowerCase(Locale.US).startsWith("image/"))) return;
    if (!isImage(name)) {
      String mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
      if (mimeExtension != null && !mimeExtension.isEmpty()) name = name + "." + mimeExtension;
    }
    String id = "img" + imageUris.size(); imageUris.put(id, uri); DocumentFile file = DocumentFile.fromSingleUri(this, uri);
    JSONObject item = new JSONObject(); item.put("name", name); item.put("path", name); item.put("uri", "native://" + id + "/" + name); item.put("lastModified", file == null ? 0 : file.lastModified()); item.put("size", file == null ? 0 : file.length()); item.put("mime", mime); output.put(item);
  }

  private String displayName(Uri uri) {
    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (column >= 0) {
          String name = cursor.getString(column);
          if (name != null && !name.isEmpty()) return name;
        }
      }
    } catch (Exception ignored) {}
    String segment = uri.getLastPathSegment();
    return segment == null ? "image" : Uri.decode(segment);
  }

  private void handleIncomingIntent(Intent intent) {
    if (intent == null || webView == null) return;
    String action = intent.getAction();
    if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
    try {
      imageUris.clear();
      JSONArray output = new JSONArray();
      ClipData clip = intent.getClipData();
      if (clip != null) {
        for (int i = 0; i < clip.getItemCount(); i++) addIncoming(clip.getItemAt(i).getUri(), output);
      } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
        java.util.ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (streams != null) for (Uri uri : streams) addIncoming(uri, output);
      } else {
        Uri uri = Intent.ACTION_SEND.equals(action) ? (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
        if (uri != null) addIncoming(uri, output);
      }
      if (output.length() == 0) {
        emitError("EDS did not provide readable images. Open or select pictures in EDS Lite, then choose Share or Open with → LumaVault.");
      } else {
        Uri first = firstIncomingUri(intent);
        emitFolder(output, isEdsUri(first) ? "eds" : "other");
      }
      setIntent(new Intent(Intent.ACTION_MAIN));
    } catch (Exception error) {
      emitError("Could not read the image shared from EDS: " + (error.getMessage() == null ? "access was denied" : error.getMessage()));
    }
  }

  private Uri firstIncomingUri(Intent intent) {
    ClipData clip = intent.getClipData();
    if (clip != null && clip.getItemCount() > 0) return clip.getItemAt(0).getUri();
    if (Intent.ACTION_VIEW.equals(intent.getAction())) return intent.getData();
    if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
      java.util.ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      return streams == null || streams.isEmpty() ? null : streams.get(0);
    }
    return (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
  }

  private void addIncoming(Uri uri, JSONArray output) throws Exception {
    if (uri != null) addSingle(uri, displayName(uri), output);
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIncomingIntent(intent);
  }

  private void emitFolder(JSONArray output, String source) { emitFolder(output, source, ""); }
  private void emitFolder(JSONArray output, String source, String warning) { emit("window.onNativeFolder(JSON.parse(" + JSONObject.quote(output.toString()) + "), " + JSONObject.quote(source) + ", " + JSONObject.quote(warning) + ")"); }

  private class Bridge {
    @JavascriptInterface public String getDiagnosticLog() { return diagnosticLog.snapshot(); }
    @JavascriptInterface public void logEvent(String message) { diagnosticLog.add(message); }
    @JavascriptInterface public void clearDiagnosticLog() { diagnosticLog.clear(); diagnosticLog.add("Log cleared by user"); }
    @JavascriptInterface public void exportDiagnosticLog() {
      runOnUiThread(() -> {
        try {
          Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("text/plain");
          intent.putExtra(Intent.EXTRA_TITLE, "LumaVault-diagnostics.txt");
          startActivityForResult(intent, SAVE_LOG);
        } catch (Exception error) {
          String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
          diagnosticLog.add("Could not launch Android save dialog: " + detail);
          emit("window.onDiagnosticExported(false, " + JSONObject.quote(detail) + ")");
        }
      });
    }
    @JavascriptInterface public String edsStatus() {
      String[] authorities = {"com.sovworks.eds.android.providers.documents.lite", "com.sovworks.eds.android.providers.documents"};
      for (String authority : authorities) {
        try { if (getPackageManager().resolveContentProvider(authority, 0) != null) return "provider"; }
        catch (Exception ignored) {}
      }
      String[] packages = {"com.sovworks.edslite", "com.sovworks.eds.android", "com.sovworks.projecteds"};
      for (String packageName : packages) { try { getPackageManager().getPackageInfo(packageName, 0); return "installed"; } catch (Exception ignored) {} }
      return "missing";
    }
    @JavascriptInterface public void pickFolder() {
      runOnUiThread(() -> { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); startActivityForResult(intent, PICK_TREE); });
    }
    @JavascriptInterface public void pickPictures() {
      runOnUiThread(() -> { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("image/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(intent, PICK_FILES); });
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == SAVE_LOG) {
      if (resultCode != RESULT_OK || data == null || data.getData() == null) {
        diagnosticLog.add("Diagnostic log export canceled or no destination selected");
        emit("window.onDiagnosticExported(false, 'Export canceled or no save location selected.')");
        return;
      }
      try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "w")) {
        if (stream == null) throw new IOException("Android returned no writable output stream");
        stream.write(diagnosticLog.snapshot().getBytes(StandardCharsets.UTF_8));
        diagnosticLog.add("Diagnostic log saved successfully");
        emit("window.onDiagnosticExported(true, '')");
      } catch (Exception error) {
        String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        diagnosticLog.add("Diagnostic log export failed: " + detail);
        emit("window.onDiagnosticExported(false, " + JSONObject.quote(detail) + ")");
      }
      return;
    }
    if (resultCode != RESULT_OK || data == null) return;
    try {
      imageUris.clear();
      if (requestCode == PICK_TREE) {
        Uri tree = data.getData(); if (tree == null) throw new IllegalStateException("No folder was returned by Android");
        try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        emit("window.onNativeScanning()");
        new Thread(() -> {
          try {
            DocumentFile root = DocumentFile.fromTreeUri(this, tree);
            if (root == null) throw new IOException("Android did not return a readable folder for the selected location");
            TreeImageScanner.Result scanned = TreeImageScanner.scan(new SafNode(root, tree, true));
            JSONArray output = photoJson(scanned);
            String warning = scanned.warning();
            if (output.length() == 0 && scanned.skippedFolders > 0) throw new IOException("No readable pictures were found. " + warning);
            emitFolder(output, isEdsUri(tree) ? "eds" : "other", warning);
          } catch (Exception error) {
            emitError(error.getMessage() == null ? "Could not list the selected folder. Check that it is still open and accessible in EDS Lite." : error.getMessage());
          }
        }, "lumavault-folder-scan").start();
      } else if (requestCode == PICK_FILES) {
        JSONArray output = new JSONArray(); ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) addPicked(clip.getItemAt(i).getUri(), output);
        else if (data.getData() != null) addPicked(data.getData(), output);
        emitFolder(output, output.length() > 0 && isEdsUri(firstUri(data)) ? "eds" : "other");
      }
    } catch (Exception error) { emitError(error.getMessage() == null ? "Android could not read the selected EDS location." : error.getMessage()); }
  }

  private Uri firstUri(Intent data) { return data.getClipData() != null && data.getClipData().getItemCount() > 0 ? data.getClipData().getItemAt(0).getUri() : data.getData(); }
  private void addPicked(Uri uri, JSONArray output) throws Exception {
    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    DocumentFile file = DocumentFile.fromSingleUri(this, uri); String name = file != null && file.getName() != null ? file.getName() : uri.getLastPathSegment(); addSingle(uri, name == null ? "picture.jpg" : name, output);
  }
}
