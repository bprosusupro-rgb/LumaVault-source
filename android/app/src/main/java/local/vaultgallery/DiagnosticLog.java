package local.vaultgallery;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory diagnostics buffer; callers must never pass image names or contents. */
public final class DiagnosticLog {
  private final int capacity;
  private final ArrayDeque<String> entries = new ArrayDeque<>();

  public DiagnosticLog(int capacity) { this.capacity = Math.max(1, capacity); }

  public synchronized void add(String message) {
    String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    entries.addLast(timestamp + "  " + (message == null ? "(no details)" : message));
    while (entries.size() > capacity) entries.removeFirst();
  }

  public synchronized String snapshot() {
    StringBuilder result = new StringBuilder("LumaVault diagnostic log\n");
    for (String entry : entries) result.append(entry).append('\n');
    return result.toString();
  }

  public synchronized void clear() { entries.clear(); }
}
