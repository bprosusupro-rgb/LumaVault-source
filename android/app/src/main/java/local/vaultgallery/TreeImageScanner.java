package local.vaultgallery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recursively collects photos while preserving results when a nested folder is inaccessible. */
public final class TreeImageScanner {
  public interface Node {
    String name();
    String key();
    boolean isDirectory();
    boolean isFile();
    default boolean hasImageMime() { return false; }
    Node[] listFiles() throws Exception;
  }

  public static final class Image {
    public final Node node;
    public final String name;
    public final String path;
    private Image(Node node, String name, String path) { this.node = node; this.name = name; this.path = path; }
  }

  public static final class Result {
    public final List<Image> images = new ArrayList<>();
    public int skippedFolders;
    public String firstSkippedPath = "";
    private String firstSkippedReason = "";

    public void skip(String path, Exception error) {
      skippedFolders++;
      if (firstSkippedPath.isEmpty()) {
        firstSkippedPath = path;
        firstSkippedReason = reason(error);
      }
    }

    public String warning() {
      if (skippedFolders == 0) return "";
      return "Skipped " + skippedFolders + " unreadable folder(s); first: " + firstSkippedPath + " (" + firstSkippedReason + ")";
    }
  }

  private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>();
  static {
    String[] extensions = {"jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "heic", "heif", "svg"};
    for (String extension : extensions) IMAGE_EXTENSIONS.add(extension);
  }

  private TreeImageScanner() {}

  public static Result scan(Node root) throws IOException {
    if (root == null) throw new IOException("The selected location did not provide a folder");
    Result result = new Result();
    walk(root, "", true, new HashSet<String>(), result);
    return result;
  }

  private static void walk(Node folder, String prefix, boolean root, Set<String> visited, Result result) throws IOException {
    String folderName = safeName(folder);
    String key;
    try { key = folder.key(); }
    catch (Exception error) { key = prefix.isEmpty() ? folderName : prefix; }
    if (key != null && !visited.add(key)) return;

    Node[] children;
    try {
      children = folder.listFiles();
      if (children == null) throw new IOException("the document provider returned no folder listing");
    } catch (Exception error) {
      String label = prefix.isEmpty() ? folderName : prefix;
      if (root) throw new IOException("Cannot list selected folder '" + label + "': " + reason(error), error);
      result.skip(label, error);
      return;
    }

    for (Node child : children) {
      if (child == null) continue;
      String name = safeName(child);
      String childPath = prefix.isEmpty() ? name : prefix + "/" + name;
      try {
        if (child.isDirectory()) {
          walk(child, childPath, false, visited, result);
        } else if (child.isFile() && (isImage(name) || child.hasImageMime())) {
          result.images.add(new Image(child, name, childPath));
        }
      } catch (Exception error) {
        result.skip(childPath, error);
      }
    }
  }

  private static String safeName(Node node) {
    try {
      String name = node.name();
      return name == null || name.trim().isEmpty() ? "Unnamed" : name;
    } catch (Exception ignored) { return "Unnamed"; }
  }

  private static boolean isImage(String name) {
    int dot = name.lastIndexOf('.');
    return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.US));
  }

  private static String reason(Throwable error) {
    Throwable deepest = error;
    while (deepest.getCause() != null && deepest.getCause() != deepest) deepest = deepest.getCause();
    String message = deepest.getMessage();
    return message == null || message.trim().isEmpty() ? deepest.getClass().getSimpleName() : message;
  }
}
