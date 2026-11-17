import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scans warehouse screenshots.
 *
 *   java Main                  watch the Screenshots folder and scan every PNG that is added to it
 *   java Main <folder>         watch that folder instead
 *   java Main <file.png>       scan just that one file
 *
 * Each scan is printed and saved to a "results" folder next to the screenshots: <name>.csv (one line per slot)
 * and <name>-totals.csv (one line per item). Slots that were not recognised, that the scanner is unsure about,
 * or whose quantity could not be read are listed at the end under CHECK THESE, kept in the totals as lines of
 * their own, and saved as pictures in results/<name>-check/. Stop watching with Ctrl+C.
 */
public class Main {

    // Folder that holds the icons (the "png" folders), the manifest.csv / manifest.json files and
    // quantity-glyphs.txt. Sub-folders are searched.
    private static final Path ICONS_AND_DATA =
            Path.of("C:/Users/ninja/Links/BDOScanner/BDOScanner/icons_and_data");

    // Folder watched for new screenshots (or pass a folder or a single file as the first argument).
    private static final Path SCREENSHOTS =
            Path.of("C:/Users/ninja/Links/BDOScanner/BDOScanner/Screenshots");

    // The storage holds no Immortal items: report "Fury Draught", not "Immortal: Fury Draught" (same picture).
    private static final boolean NO_IMMORTALS = true;

    public static void main(String[] args) throws Exception {
        Path target = args.length > 0 ? Path.of(args[0]) : SCREENSHOTS;

        // Slow part (loads every icon once). Created once and reused for every screenshot.
        ItemIconRecognizer recognizer = new ItemIconRecognizer(ICONS_AND_DATA);
        recognizer.setIgnoreImmortal(NO_IMMORTALS);
        System.out.println("Loaded " + recognizer.indexedItems() + " icons");

        if (Files.isDirectory(target)) {
            watch(recognizer, target);
        } else {
            scan(recognizer, target);
        }
    }

    // ---------------------------------------------------------------- watching a folder

    /** Scans every PNG added to (or replaced in) the folder, until the program is stopped. */
    private static void watch(ItemIconRecognizer recognizer, Path folder) throws IOException, InterruptedException {
        Map<Path, Long> scanned = new HashMap<>(); // file -> last-modified time it was scanned at
        try (WatchService watcher = folder.getFileSystem().newWatchService()) {
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            System.out.println("Watching " + folder.toAbsolutePath() + " for new .png screenshots (Ctrl+C to stop)");
            while (true) {
                WatchKey key = watcher.take();
                // a single save usually raises several events; let them all arrive first
                Thread.sleep(300);
                List<Path> changed = new ArrayList<>();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Path file = folder.resolve((Path) event.context());
                    if (file.getFileName().toString().toLowerCase().endsWith(".png") && !changed.contains(file)) {
                        changed.add(file);
                    }
                }
                key.reset();
                for (Path file : changed) {
                    BufferedImage image = waitUntilReadable(file);
                    if (image == null) continue; // deleted again, or not a real image
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (Long.valueOf(modified).equals(scanned.get(file))) continue; // already scanned this version
                    scanned.put(file, modified);
                    try {
                        scan(recognizer, file, image);
                    } catch (RuntimeException | IOException e) {
                        System.out.println("Could not scan " + file.getFileName() + ": " + e);
                    }
                    System.out.println();
                    System.out.println("Watching for the next screenshot...");
                }
            }
        }
    }

    /**
     * Waits (up to 10 s) until a file that is still being written has a stable size and decodes as an image.
     * Returns null if it never does.
     */
    private static BufferedImage waitUntilReadable(Path file) throws InterruptedException {
        long lastSize = -1;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                long size = Files.size(file);
                if (size > 0 && size == lastSize) {
                    BufferedImage image = ImageIO.read(file.toFile());
                    if (image != null) return image;
                }
                lastSize = size;
            } catch (IOException e) {
                // still locked by the program saving it, or already gone
                if (!Files.exists(file)) return null;
            }
            Thread.sleep(250);
        }
        return null;
    }

    // ---------------------------------------------------------------- scanning one screenshot

    private static void scan(ItemIconRecognizer recognizer, Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IOException("Not a readable image: " + file);
        scan(recognizer, file, image);
    }

    private static void scan(ItemIconRecognizer recognizer, Path file, BufferedImage image) throws IOException {
        System.out.println("=== " + file.getFileName());

        // Find the slot grid of the market warehouse panel wherever it is in the capture, at any in-game UI size.
        // (Use ItemIconRecognizer.GridLayout.STORAGE for the 465-pixel-wide stitched storage image.)
        ItemIconRecognizer.GridLayout layout =
                ItemIconRecognizer.GridLayout.detect(image, ItemIconRecognizer.GridLayout.WAREHOUSE_PANEL);
        if (!recognizer.hasQuantityGlyphsFor(layout.cellSize())) {
            System.out.println("!! This screenshot uses a UI size (" + layout.cellSize() + " px slots) that has no digit"
                    + " examples yet, so quantities will be unreliable. Label one screenshot at this size and run:");
            System.out.println("!!   java QuantityReader <screenshot.png> <labels.txt> ../icons_and_data/quantity-glyphs-"
                    + layout.cellSize() + ".txt");
        }
        System.out.printf("Grid: first slot at (%.0f, %.0f), %d px slots, pitch %.2f x %.2f, %d x %d%n",
                layout.firstX(), layout.firstY(), layout.cellSize(), layout.pitch(), layout.pitchY(),
                layout.columns(), layout.rows());
        List<ItemIconRecognizer.Match> results = recognizer.recognise(image, layout, false);

        // per cell
        for (ItemIconRecognizer.Match m : results) {
            String label = switch (m.status()) {
                case NOT_RECOGNISED -> unnamedIcon(m) ? "!! NO NAME: looks like icon " + m.item().iconId()
                        + ", which no manifest names"
                        : "!! NOT RECOGNISED" + (m.item() != null && m.item().name() != null
                        ? " (closest: " + m.item().name() + ", probably wrong)" : "");
                case UNSURE -> nameOf(m.item()) + "  ?? UNSURE, check it";
                default -> nameOf(m.item()) + String.format("  [%.0f%%]", m.confidence() * 100);
            };
            if (m.recognised() && !m.alternatives().isEmpty()) label += "  or: " + alternativesOf(m);
            if (!quantityRead(m)) label += "  !! quantity not readable";
            System.out.printf("row %d, col %d -> %-8s %s%n", m.row(), m.column(), quantityOf(m), label);
        }

        // totals per item (several cells can hold the same item); slots that were not recognised are kept
        // as lines of their own so their amounts are not lost
        Map<String, long[]> totals = new LinkedHashMap<>(); // name -> {sum, rounded?, unsure?}
        for (ItemIconRecognizer.Match m : results) {
            String key = m.recognised() ? nameOf(m.item())
                    : "NOT RECOGNISED (row " + m.row() + ", col " + m.column() + ")";
            long[] t = totals.computeIfAbsent(key, k -> new long[3]);
            if (quantityRead(m)) t[0] += m.quantity().value();
            if (m.quantity() != null && m.quantity().approximate()) t[1] = 1;
            if (m.status() == ItemIconRecognizer.Status.UNSURE) t[2] = 1;
        }
        System.out.println();
        System.out.println("Totals (" + totals.size() + " lines, ~ = rounded by the game, ?? = includes an unsure slot):");
        totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("  %12s  %s%s%n",
                        (e.getValue()[1] == 1 ? "~" : "") + String.format("%,d", e.getValue()[0]), e.getKey(),
                        e.getValue()[2] == 1 ? "  ??" : ""));

        List<String> checks = saveResults(file, image, layout, results, totals);

        // everything that needs a look, last so it is easy to find
        System.out.println();
        if (checks.isEmpty()) {
            System.out.println("All " + results.size() + " slots recognised and their quantities read.");
        } else {
            System.out.println("CHECK THESE (" + checks.size() + " of " + results.size() + " slots):");
            checks.forEach(c -> System.out.println("  " + c));
        }
    }

    /**
     * Writes results/<name>.csv (one line per slot) and results/<name>-totals.csv next to the screenshot, and a
     * picture of every slot that needs a look to results/<name>-check/. Returns one line per such slot.
     */
    private static List<String> saveResults(Path file, BufferedImage image, ItemIconRecognizer.GridLayout layout,
                                            List<ItemIconRecognizer.Match> results, Map<String, long[]> totals)
            throws IOException {
        Path dir = file.toAbsolutePath().getParent().resolve("results");
        Files.createDirectories(dir);
        String name = file.getFileName().toString().replaceFirst("(?i)\\.png$", "");

        List<String> slots = new ArrayList<>();
        slots.add("row,column,status,quantity,quantity_value,item,confidence,alternatives,closest_guess");
        for (ItemIconRecognizer.Match m : results) {
            slots.add(String.join(",",
                    String.valueOf(m.row()), String.valueOf(m.column()), m.status().toString(),
                    csv(quantityOf(m)), m.quantity() == null ? "" : String.valueOf(m.quantity().value()),
                    csv(m.recognised() ? nameOf(m.item()) : ""),
                    m.recognised() ? String.format("%.2f", m.confidence()) : "",
                    csv(m.recognised() ? alternativesOf(m) : ""),
                    csv(!m.recognised() && m.item() != null && m.item().name() != null ? m.item().name() : "")));
        }
        Files.write(dir.resolve(name + ".csv"), slots, StandardCharsets.UTF_8);

        List<String> sums = new ArrayList<>();
        sums.add("item,total,rounded,unsure");
        totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sums.add(csv(e.getKey()) + "," + e.getValue()[0] + "," + (e.getValue()[1] == 1)
                        + "," + (e.getValue()[2] == 1)));
        Files.write(dir.resolve(name + "-totals.csv"), sums, StandardCharsets.UTF_8);
        System.out.println("Saved " + dir.resolve(name + ".csv") + " and " + name + "-totals.csv");

        // pictures of the slots that need a look (old ones from an earlier scan of this file are replaced)
        Path checkDir = dir.resolve(name + "-check");
        if (Files.isDirectory(checkDir)) {
            try (var old = Files.list(checkDir)) {
                for (Path p : old.collect(Collectors.toList())) Files.delete(p);
            }
        }
        List<String> checks = new ArrayList<>();
        for (ItemIconRecognizer.Match m : results) {
            String why = unnamedIcon(m) ? "looks like icon " + m.item().iconId() + ", which has no name in the data"
                    : m.status() == ItemIconRecognizer.Status.NOT_RECOGNISED ? "not recognised (not in the data?)"
                    : m.status() == ItemIconRecognizer.Status.UNSURE ? "unsure: is it " + nameOf(m.item()) + "?"
                    : !quantityRead(m) ? nameOf(m.item()) + ": quantity not readable" : null;
            if (why == null) continue;
            if (m.status() != ItemIconRecognizer.Status.RECOGNISED && !quantityRead(m)) why += "; quantity not readable";
            Files.createDirectories(checkDir);
            Path pic = checkDir.resolve("row" + m.row() + "-col" + m.column() + ".png");
            ImageIO.write(slotPicture(image, layout, m), "png", pic.toFile());
            checks.add(String.format("row %d, col %d  qty %-8s %s   -> %s", m.row(), m.column(), quantityOf(m), why,
                    dir.getParent().relativize(pic)));
        }
        return checks;
    }

    /** The slot (with its frame) enlarged 3x, so it is easy to see what it holds. */
    private static BufferedImage slotPicture(BufferedImage image, ItemIconRecognizer.GridLayout layout,
                                             ItemIconRecognizer.Match m) {
        int pad = 3;
        int x = (int) Math.round(layout.firstX() + (m.column() - 1) * layout.pitch()) - pad;
        int y = (int) Math.round(layout.firstY() + (m.row() - 1) * layout.pitchY()) - pad;
        int s = layout.cellSize() + 2 * pad;
        x = Math.max(0, Math.min(x, image.getWidth() - s));
        y = Math.max(0, Math.min(y, image.getHeight() - s));
        BufferedImage big = new BufferedImage(s * 3, s * 3, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = big.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(image.getSubimage(x, y, s, s), 0, 0, s * 3, s * 3, null);
        g.dispose();
        return big;
    }

    /** A close match to an icon that is in the data folder but that no manifest gives a name. */
    private static boolean unnamedIcon(ItemIconRecognizer.Match m) {
        return m.status() == ItemIconRecognizer.Status.NOT_RECOGNISED && m.item() != null && m.item().name() == null
                && m.emptyRatio() < ItemIconRecognizer.NOT_RECOGNISED_RATIO;
    }

    private static boolean quantityRead(ItemIconRecognizer.Match m) {
        return m.quantity() != null && m.quantity().value() >= 0;
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String quantityOf(ItemIconRecognizer.Match m) {
        return m.quantity() == null || m.quantity().text().isEmpty() ? "?" : m.quantity().text();
    }

    private static String alternativesOf(ItemIconRecognizer.Match m) {
        return m.alternatives().stream().map(Main::nameOf).distinct().collect(Collectors.joining(", "));
    }

    private static String nameOf(ItemIconRecognizer.ItemInfo i) {
        return i.name() != null ? i.name() : "icon " + i.iconId() + " (no name in manifests)";
    }
}
