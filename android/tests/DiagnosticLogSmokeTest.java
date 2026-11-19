package local.vaultgallery;

public final class DiagnosticLogSmokeTest {
  public static void main(String[] args) {
    DiagnosticLog log = new DiagnosticLog(3);
    log.add("one");
    log.add("two");
    log.add("three");
    log.add("four");
    require(!log.snapshot().contains("one"), "oldest entry is evicted at capacity");
    require(log.snapshot().contains("four"), "latest entry is retained");
    log.clear();
    require(!log.snapshot().contains("four"), "clear removes entries");
    System.out.println("PASS: diagnostic log bounds, export snapshot, and clear");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
