package local.vaultgallery;

import java.util.ArrayList;
import java.util.List;

public final class TreeImageScannerSmokeTest {
  private static final class Folder implements TreeImageScanner.Node {
    final String name;
    final String key;
    final List<TreeImageScanner.Node> children = new ArrayList<>();
    Exception listingFailure;

    Folder(String name, String key) { this.name = name; this.key = key; }
    @Override public String name() { return name; }
    @Override public String key() { return key; }
    @Override public boolean isDirectory() { return listingFailure != null || !children.isEmpty(); }
    @Override public boolean isFile() { return false; }
    @Override public TreeImageScanner.Node[] listFiles() throws Exception {
      if (listingFailure != null) throw listingFailure;
      return children.toArray(new TreeImageScanner.Node[0]);
    }
  }

  private static final class Photo implements TreeImageScanner.Node {
    final String name;
    final String key;
    Photo(String name, String key) { this.name = name; this.key = key; }
    @Override public String name() { return name; }
    @Override public String key() { return key; }
    @Override public boolean isDirectory() { return false; }
    @Override public boolean isFile() { return true; }
    @Override public TreeImageScanner.Node[] listFiles() { throw new AssertionError("Photos are not folders"); }
  }

  public static void main(String[] args) throws Exception {
    Folder root = new Folder("Vault", "vault");
    root.children.add(new Photo("cover.jpg", "cover"));
    Folder album = new Folder("Album", "album");
    album.children.add(new Photo("trip.png", "trip"));
    Folder unreadable = new Folder("Android", "android");
    unreadable.listingFailure = new SecurityException("permission denied");
    album.children.add(unreadable);
    root.children.add(album);

    TreeImageScanner.Result result = TreeImageScanner.scan(root);
    require(result.images.size() == 2, "readable photos survive a nested-folder access failure");
    require("cover.jpg".equals(result.images.get(0).path), "root photo keeps its relative path");
    require("Album/trip.png".equals(result.images.get(1).path), "nested photo keeps its relative path");
    require(result.skippedFolders == 1, "unreadable folder is counted once");
    require("Album/Android".equals(result.firstSkippedPath), "warning identifies the unreadable folder");
    require(result.warning().contains("Album/Android"), "partial-scan warning names the skipped folder");

    Folder deniedRoot = new Folder("Vault", "denied-root");
    deniedRoot.listingFailure = new SecurityException("permission denied");
    try {
      TreeImageScanner.scan(deniedRoot);
      throw new AssertionError("a root access failure must be reported");
    } catch (Exception expected) {
      require(expected.getMessage().contains("Vault"), "root failure names the selected folder");
      require(expected.getMessage().contains("permission denied"), "root failure preserves the provider reason");
    }

    System.out.println("PASS: readable photos survive inaccessible folders; partial and root errors are actionable");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
