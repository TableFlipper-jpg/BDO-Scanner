import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Identifies items from their icons in a game screenshot.
 *
 * Reference icons are named by item id ("4001.png"). Item details can optionally come from
 * item data JSON files; without them the result is just the item id. Every reference icon is
 * reduced to a perceptual fingerprint once at start-up; each screenshot cell is then matched
 * by a fast hash shortlist followed by a colour-thumbnail comparison.
 *
 * No dependencies beyond the JDK (Java 17+).
 */
public final class ItemIconRecognizer {

    // ---------------------------------------------------------------- public types

    /**
     * Item details, keyed by icon number (the icon's file name, "00000613.png" -> 613).
     * name is null when no manifest names this icon; grade is -1 and category null when unknown.
     */
    public record ItemInfo(int iconId, String name, int grade, String category) { }

    /**
     * Where the cells are in the screenshot. row/column in results are 1-based.
     * firstX/firstY: top-left corner of the first cell. cellSize: width/height of one cell in the screenshot
     * (cells of any size are scaled to the reference size, so smaller or larger UI scales work).
     * pitch / pitchY: distance from one cell's left (top) edge to the next (cell size + gap); may be fractional.
     * rows <= 0 means "as many rows as fit in the image".
     */
    public record GridLayout(double firstX, double firstY, double pitch, double pitchY, int cellSize, int columns, int rows) {
        /** Same pitch across and down. */
        public GridLayout(double firstX, double firstY, double pitch, int cellSize, int columns, int rows) {
            this(firstX, firstY, pitch, pitch, cellSize, columns, rows);
        }

        /** Cells are 45 px unless stated otherwise. */
        public GridLayout(double firstX, double firstY, double pitch, int columns, int rows) {
            this(firstX, firstY, pitch, 45, columns, rows);
        }

        /** Warehouse / storage screen as stitched by the old Python scanner (465 px wide). */
        public static final GridLayout STORAGE = new GridLayout(9, 6, 51, 45, 9, 0);
        /** Inventory screen (442 x 442 capture). */
        public static final GridLayout INVENTORY = new GridLayout(10, 10, 55, 45, 8, 8);
        /**
         * Central-market warehouse panel (391 x 515 capture including the "LT" header). For other crops of the
         * same panel use {@link #detect}, which finds where the grid actually starts.
         */
        public static final GridLayout WAREHOUSE_PANEL = new GridLayout(10, 56, 40.7, 34, 9, 9);

        /**
         * Finds the grid in a screenshot from the thin frame line drawn round every slot (also round empty and
         * locked ones), so the capture can be cropped anywhere. template supplies the expected pitch and cell
         * size (UI scale, within +-10%) and the number of columns; rows are counted. Returns template unchanged
         * if no grid is found.
         */
        public static GridLayout detect(BufferedImage img, GridLayout template) {
            // the template's size first, then bigger and smaller UI scales / capture sizes (each try covers +-10%)
            for (double scale : new double[]{1.0, 1.2, 0.8, 1.4, 1.6, 0.65}) {
                GridLayout scaled = new GridLayout(template.firstX(), template.firstY(), template.pitch() * scale,
                        template.pitchY() * scale, (int) Math.round(template.cellSize() * scale), template.columns(),
                        template.rows());
                GridLayout found = detectAt(img, scaled);
                if (found != null) return found;
            }
            return template;
        }

        private static GridLayout detectAt(BufferedImage img, GridLayout template) {
            double[] cols = lineProfile(img, true), rows = lineProfile(img, false);
            // a slot frame is the icon box plus a 1-px line on each side
            double frameRatio = (template.cellSize() + 1.0) / template.pitch();
            double[] x = fitFrames(cols, template.pitch(), frameRatio, template.columns());
            double[] y = fitFrames(rows, template.pitchY(), frameRatio, 0);
            if (x == null || y == null) return null;
            int cell = (int) Math.round(template.cellSize() * x[1] / template.pitch());
            return new GridLayout(x[0] + 1, y[0] + 1, x[1], y[1], cell, template.columns(), (int) y[2]);
        }

        /** For each column (or row) of pixels: how many pixels are a thin line brighter than both neighbours. */
        private static double[] lineProfile(BufferedImage img, boolean columns) {
            int w = img.getWidth(), h = img.getHeight();
            double[] p = new double[columns ? w : h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int c = lum(img.getRGB(x, y));
                    int a = columns ? lum(img.getRGB(x - 1, y)) : lum(img.getRGB(x, y - 1));
                    int b = columns ? lum(img.getRGB(x + 1, y)) : lum(img.getRGB(x, y + 1));
                    if (c - a > 8 && c - b > 8) p[columns ? x : y]++;
                }
            }
            return p;
        }

        private static int lum(int p) {
            return (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
        }

        /**
         * Best {first frame line, pitch, count} for frames whose two edges are frameRatio * pitch apart. count is
         * fixed when given (> 0), otherwise the number of consecutive frames found. null if nothing frame-like.
         */
        private static double[] fitFrames(double[] profile, double pitch, double frameRatio, int count) {
            double max = 0;
            for (double v : profile) max = Math.max(max, v);
            if (max == 0) return null;
            // frame lines: local maxima of the profile
            List<Integer> lines = new ArrayList<>();
            for (int i = 1; i + 1 < profile.length; i++) {
                if (profile[i] >= 0.3 * max && profile[i] >= profile[i - 1] && profile[i] > profile[i + 1]) lines.add(i);
            }
            double[] best = null;
            for (double p = pitch * 0.9; p <= pitch * 1.1; p += 0.05) {
                int span = (int) Math.round(p * frameRatio) - 1;
                // left (top) edges: lines with the other edge of the frame one slot width further on
                List<Integer> starts = new ArrayList<>();
                for (int a : lines) {
                    for (int b : lines) if (Math.abs(b - a - span) <= 1) { starts.add(a); break; }
                }
                // longest run of starts spaced one pitch apart; one missing line at a time is allowed (an icon
                // can hide part of a frame), but the run must not start or end with a gap
                for (int i = 0; i < starts.size(); i++) {
                    int first = starts.get(i), last = first, slots = 1, found = 1;
                    for (int j = i + 1; j < starts.size(); j++) {
                        int s = starts.get(j);
                        long k = Math.round((s - first) / p);
                        if (k <= slots - 1) continue;
                        if (k > slots + 1) break;           // two or more frames missing in a row: run ends
                        if (Math.abs(s - (first + k * p)) > 1.5) continue;
                        if (count > 0 && k >= count) break;
                        last = s;
                        slots = (int) k + 1;
                        found++;
                    }
                    if (slots < 2 || found < 2 || (count > 0 && slots < count)) continue;
                    if (best == null || slots > best[2] || (slots == best[2] && found > best[3])) {
                        best = new double[]{first, (last - first) / (double) (slots - 1), slots, found};
                    }
                }
            }
            return best;
        }
    }

    /**
     * EMPTY: nothing in the slot. RECOGNISED: a confident match. UNSURE: matched, but not clearly enough to trust
     * without a look. NOT_RECOGNISED: probably an item that is not in the data (item is then only the closest guess).
     */
    public enum Status { EMPTY, RECOGNISED, UNSURE, NOT_RECOGNISED }

    /**
     * Result for one cell. item: the matched item (for NOT_RECOGNISED the closest one, which is probably wrong;
     * null for EMPTY). distance: 0 = identical, larger = worse. emptyRatio: distance divided by the distance to an
     * empty slot, the measure the status is decided on. lead: how much closer the match is than the nearest icon
     * that is a different picture (that one's distance divided by the match's; 1 = a tie). confidence: 0..1.
     * alternatives: other items drawn with the same (or a visually identical) icon, e.g. Garlic / Special Garlic,
     * which cannot be told apart from the picture alone.
     * quantity: the stack size printed on the cell, or null if no glyph file was loaded or the cell is empty.
     */
    public record Match(int row, int column, Status status, ItemInfo item, double distance, double emptyRatio,
                        double lead, double confidence, List<ItemInfo> alternatives, QuantityReader.Quantity quantity) {
        public boolean empty() { return status == Status.EMPTY; }

        /** RECOGNISED or UNSURE: item is the answer. */
        public boolean recognised() { return status == Status.RECOGNISED || status == Status.UNSURE; }

        Match withQuantity(QuantityReader.Quantity q) {
            return new Match(row, column, status, item, distance, emptyRatio, lead, confidence, alternatives, q);
        }
    }

    // ---------------------------------------------------------------- tuning

    private static final int CELL = 45;                       // an item cell is 45 x 45 px
    private static final int R0 = 3, RS = 39;                 // part of the cell compared: skips the grade border
    private static final int G = 13;                          // colour thumbnail is G x G blocks of 3 x 3 px
    private static final int TEXT_TOP = 22;                   // quantity text only appears below this row of the compared part
    private static final double MIN_BLOCK_WEIGHT = 0.34;      // blocks mostly hidden by text are not compared
    private static final double SAME_ICON = 0.0015;           // icons closer than this are treated as the same picture
    private static final double DUPLICATE_PICTURE = 0.02;     // copies of one icon (same number and names) this close load once
    private static final double DIFFERENT_PICTURE = 0.04;     // icons further apart than this are different pictures (for lead)
    private static final double HIDDEN_CLOSE = 0.04, HIDDEN_LEAD = 1.2; // a mostly hidden icon this close and this far ahead is UNSURE
    private static final float CHROMA_WEIGHT = 2f;            // colour differences count this much more than brightness
    private static final int RERANK = 8;                      // at most this many close candidates are compared again...
    private static final double RERANK_MARGIN = 0.015;        // ...if within this distance of the best one
    // A match's distance divided by an empty slot's distance (see match()). Correct matches measured 0.15 - 0.52
    // (the high end: icons mostly hidden by the quantity), wrong ones from 0.36 up; the bands in between are UNSURE.
    private static final double UNSURE_RATIO = 0.35;
    public static final double NOT_RECOGNISED_RATIO = 0.55;
    private static final Color CELL_BACKGROUND = new Color(30, 30, 33); // transparent icons are drawn on this
    private static final int GLOW_ALPHA = 200;                // reference pixels less opaque than this are glow, not icon
    /** An empty slot: what a cell is compared with to tell how much a match explains. */
    private static final Feature EMPTY_SLOT =
            featureOf(toCell(new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB)), false);
    private static final double EMPTY_STD = 15.0;             // grey-level spread below this = empty or locked slot

    /** Cells whose best distance is above this are reported as not recognised (whatever their empty ratio). */
    private double maxDistance = 0.09;

    public void setMaxDistance(double maxDistance) { this.maxDistance = maxDistance; }
    public double getMaxDistance() { return maxDistance; }

    // ---------------------------------------------------------------- index

    /** thumb: G x G blocks of r,g,b. weight: per block, the share of pixels not hidden by quantity text (0..1). */
    private record Feature(float[] thumb, float[] weight, double grayStd) { }
    /** One reference icon. names: every item drawn with this icon (first one is reported as the match). */
    private record Entry(List<ItemInfo> names, Feature feature) {
        ItemInfo info() { return names.get(0); }
    }

    private final List<Entry> entries;
    /** Stack-size readers by the slot size (in-game UI size) their example glyphs were cut at. */
    private final Map<Integer, QuantityReader> quantityReaders = new TreeMap<>();

    /** Replaces the stack-size readers loaded from the glyph files (null: don't read stack sizes). */
    public void setQuantityReader(QuantityReader quantities) {
        quantityReaders.clear();
        if (quantities != null) quantityReaders.put(quantities.box(), quantities);
    }

    /** Adds (or replaces) the stack-size reader for the slot size it was made at. */
    public void addQuantityReader(QuantityReader quantities) { quantityReaders.put(quantities.box(), quantities); }

    /**
     * True if digit examples exist for slots of this size (+-1 px). Without them the quantities are read by
     * scaling the slot to the nearest size that has examples, which is unreliable.
     */
    public boolean hasQuantityGlyphsFor(int slotSize) {
        return quantityReaders.keySet().stream().anyMatch(b -> Math.abs(b - slotSize) <= 1);
    }

    /** The reader made at the size nearest to slotSize (null if there is none). */
    private QuantityReader quantityReaderFor(int slotSize) {
        QuantityReader best = null;
        for (var e : quantityReaders.entrySet()) {
            if (best == null || Math.abs(e.getKey() - slotSize) < Math.abs(best.box() - slotSize)) best = e.getValue();
        }
        return best;
    }

    private boolean ignoreImmortal;

    /**
     * Immortal items use the same picture as the normal item, and the data often only lists the Immortal one.
     * When the storage holds no Immortal items, set this to report "Fury Draught" instead of
     * "Immortal: Fury Draught" (the "Immortal: " part is simply removed from the name).
     */
    public void setIgnoreImmortal(boolean ignoreImmortal) { this.ignoreImmortal = ignoreImmortal; }

    /** Name of the optional file (in dataRoot) that renames items: "scanner name,your name" per line. */
    public static final String NAME_OVERRIDES = "name-overrides.csv";

    private final Map<String, String> nameOverrides = new HashMap<>(); // lower-case scanner name -> name to show

    /** Name of the glyph file (in dataRoot) used to read stack sizes; see {@link QuantityReader}. */
    public static final String QUANTITY_GLYPHS = "quantity-glyphs.txt";

    /**
     * @param dataRoot folder that holds the icons ("00000613.png", sub-folders are searched too) and the
     *                 manifest.csv / manifest.json files. Manifest rows link an item name to its icon through
     *                 a "file", "icon" or "icon_url" column; the icon number in that path is the key.
     *                 If it also holds {@value #QUANTITY_GLYPHS}, stack sizes are read too.
     */
    public ItemIconRecognizer(Path dataRoot) throws IOException {
        // quantity-glyphs.txt (standard UI size) and quantity-glyphs-<slot size>.txt for other in-game UI sizes
        try (var files = Files.list(dataRoot)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("quantity-glyphs(-\\d+)?\\.txt")).toList()) {
                QuantityReader r = QuantityReader.load(f);
                quantityReaders.put(r.box(), r);
            }
        }
        Path overrides = dataRoot.resolve(NAME_OVERRIDES);
        if (Files.exists(overrides)) {
            for (String line : Files.readAllLines(overrides, StandardCharsets.UTF_8)) {
                line = line.replace("﻿", "").trim();
                int comma = line.indexOf(',');
                if (line.isEmpty() || line.startsWith("#") || comma < 0) continue;
                String from = line.substring(0, comma).trim(), to = line.substring(comma + 1).trim();
                if (!from.equalsIgnoreCase("scanner name") && !to.isEmpty()) nameOverrides.put(from.toLowerCase(), to);
            }
        }

        List<Path> pictures = new ArrayList<>();
        List<Path> textFiles = new ArrayList<>();
        try (var walk = Files.walk(dataRoot)) {
            walk.filter(Files::isRegularFile).sorted().forEach(f -> {
                String low = f.getFileName().toString().toLowerCase();
                if (low.endsWith(".png") && iconNumber(low) != null) {
                    pictures.add(f);
                } else if (low.endsWith(".json") || low.endsWith(".csv")) {
                    textFiles.add(f);
                }
            });
        }

        // icon key (see iconKeys) -> distinct item names drawn with that icon (in file order)
        Map<String, Map<String, ItemInfo>> names = new HashMap<>();
        for (Path f : textFiles) {
            try {
                if (Files.size(f) > 200_000_000L) continue;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                List<NamedIcon> rows = new ArrayList<>();
                if (f.getFileName().toString().toLowerCase().endsWith(".csv")) {
                    collectNamesCsv(text, rows);
                } else {
                    collectNames(new Json(text).parse(), rows, 0);
                }
                for (NamedIcon row : rows) {
                    for (String key : row.keys()) {
                        names.computeIfAbsent(key, k -> new LinkedHashMap<>())
                                .merge(row.info().name().toLowerCase(), row.info(), (a, b) -> a.grade() >= 0 ? a : b);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // not a format we understand: skip it
            }
        }

        List<Entry> loaded = pictures.parallelStream()
                .map(p -> {
                    try {
                        BufferedImage img = ImageIO.read(p.toFile());
                        if (img == null) return null;
                        String file = p.getFileName().toString();
                        int number = iconNumber(file);
                        // names given for exactly this picture, else for its icon number
                        Map<String, ItemInfo> known = null;
                        for (String key : iconKeys(file)) {
                            if ((known = names.get(key)) != null) break;
                        }
                        List<ItemInfo> infos = known == null
                                ? List.of(new ItemInfo(number, null, -1, null))
                                : List.copyOf(known.values());
                        return new Entry(infos, featureOf(toCell(withoutGlow(img)), false));
                    } catch (IOException e) {
                        return null;
                    }
                })
                .filter(e -> e != null)
                .sorted(Comparator.comparingInt(e -> e.info().iconId()))
                .collect(Collectors.toList());

        // the same picture is often in two folders ("00000613.png" and "08_potion_00000613.png"), each with its
        // own manifest: keep it once, with the names from both
        List<Entry> unique = new ArrayList<>();
        for (Entry e : loaded) {
            boolean duplicate = false;
            for (int i = unique.size() - 1; i >= 0 && unique.get(i).info().iconId() == e.info().iconId(); i--) {
                Entry u = unique.get(i);
                if (distance(u.feature(), e.feature()) <= DUPLICATE_PICTURE) {
                    unique.set(i, new Entry(mergedNames(u.names(), e.names()), u.feature()));
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) unique.add(e);
        }
        this.entries = unique;
        if (entries.isEmpty()) {
            throw new IOException("No icons found. Expected files like 00000613.png inside: " + dataRoot);
        }
    }

    /** Both lists' names, each once (by name); a placeholder without a name only survives if there is nothing else. */
    private static List<ItemInfo> mergedNames(List<ItemInfo> a, List<ItemInfo> b) {
        Map<String, ItemInfo> out = new LinkedHashMap<>();
        for (ItemInfo i : a) if (i.name() != null) out.putIfAbsent(i.name().toLowerCase(), i);
        for (ItemInfo i : b) if (i.name() != null) out.putIfAbsent(i.name().toLowerCase(), i);
        return out.isEmpty() ? a : List.copyOf(out.values());
    }

    /** A name row from a manifest: the item and the icon keys (see iconKeys) its icon file is known by. */
    private record NamedIcon(List<String> keys, ItemInfo info) { }

    /**
     * How an icon file is looked up, most specific first: by its exact file name, then (only for files named by
     * number alone, like "00000613.png" or "00015230_1.png", which the recipe manifests use) by icon number.
     * A file like "ui_artwork_ic_00004003.png" is a different picture from "07_productmaterial_00004003.png", so
     * prefixed names are only ever matched exactly.
     */
    private static List<String> iconKeys(String path) {
        String n = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        String stem = (dot > 0 ? n.substring(0, dot) : n).toLowerCase();
        Integer number = iconNumber(stem);
        if (number == null) return List.of();
        return stem.matches("\\d+(_[0-9a-z]+)?") ? List.of("file:" + stem, "#" + number) : List.of("file:" + stem);
    }

    /**
     * Icon picture names (without ".png"): the icon number, optionally after a category prefix and followed by a
     * variant suffix: "00000613", "08_potion_00000613", "03_etc_00756425_1". Group 1: number, group 2: suffix.
     * Pictures without such a number (e.g. "06_housing_inhouse_z_booty_bearhead_01") can't be linked to a name.
     */
    private static final java.util.regex.Pattern ICON_FILE =
            java.util.regex.Pattern.compile("(?i)(?:[0-9a-z_]*?_)?(\\d{5,9})((?:_[0-9a-z]+)?)");

    /**
     * "00000613.png", "webp/00015230_1.webp" or a full icon URL -> the icon number (613, 15230); null if the name
     * holds no icon number (same rule as {@link #ICON_FILE}).
     */
    private static Integer iconNumber(String path) {
        String n = path.substring(path.replace('\\', '/').lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (n.matches("\\d{1,9}")) return Integer.parseInt(n);
        java.util.regex.Matcher m = ICON_FILE.matcher(n);
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }

    /** Number of items that have both item details and an icon. */
    public int indexedItems() { return entries.size(); }

    // ---------------------------------------------------------------- recognition API

    /** Reads every cell of the grid in a screenshot file and returns one Match per non-empty-or-unknown cell. */
    public List<Match> recognise(Path screenshot, GridLayout layout) throws IOException {
        BufferedImage img = ImageIO.read(screenshot.toFile());
        if (img == null) throw new IOException("Not a readable image: " + screenshot);
        return recognise(img, layout, false);
    }

    /** Reads every cell of the grid. If includeEmpty is false, empty slots are left out of the result. */
    public List<Match> recognise(BufferedImage screenshot, GridLayout layout, boolean includeEmpty) {
        int size = layout.cellSize();
        int rows = layout.rows() > 0
                ? layout.rows()
                : Math.max(0, (int) ((screenshot.getHeight() - layout.firstY() + (layout.pitchY() - size)) / layout.pitchY()));

        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < layout.columns(); c++) {
                int x = (int) Math.round(layout.firstX() + c * layout.pitch());
                int y = (int) Math.round(layout.firstY() + r * layout.pitchY());
                if (x >= 0 && y >= 0 && x + size <= screenshot.getWidth() && y + size <= screenshot.getHeight()) {
                    cells.add(new int[]{r + 1, c + 1, x, y});
                }
            }
        }

        List<CellData> data = cells.parallelStream()
                .map(cell -> {
                    int grade = borderGrade(screenshot, cell[2], cell[3], size);
                    // the cell plus copies shifted by one screenshot pixel, to absorb small grid misalignment
                    List<Feature> shifted = new ArrayList<>();
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int x = cell[2] + dx, y = cell[3] + dy;
                            if (x < 0 || y < 0 || x + size > screenshot.getWidth() || y + size > screenshot.getHeight()) continue;
                            BufferedImage sub = screenshot.getSubimage(x, y, size, size);
                            Feature f = featureOf(size == CELL ? sub : toCell(sub), true);
                            if (dx == 0 && dy == 0) shifted.add(0, f); else shifted.add(f);
                        }
                    }
                    return new CellData(cell[0], cell[1], cell[2], cell[3], grade, shifted);
                })
                .collect(Collectors.toList());

        QuantityReader quantities = quantityReaderFor(size);
        return data.parallelStream()
                .map(c -> {
                    Match m = match(c.features(), c.grade(), c.row(), c.column());
                    return m.empty() || quantities == null ? m : m.withQuantity(quantities.read(screenshot, c.x(), c.y(), size));
                })
                .filter(m -> includeEmpty || !m.empty())
                .sorted(Comparator.comparingInt(Match::row).thenComparingInt(Match::column))
                .collect(Collectors.toList());
    }

    /** Identifies a single icon image (any size; it is scaled to a 45 x 45 cell). */
    public Match identify(BufferedImage icon) {
        return match(List.of(featureOf(toCell(icon), true)), -1, 0, 0);
    }

    private record CellData(int row, int column, int x, int y, int grade, List<Feature> features) { }
    private record Best(Entry entry, Feature cell, double distance) { }


    /**
     * Item grade from the colour of the 1-px frame just outside the icon box: 1 green, 2 blue, 3 gold, 4 orange.
     * -1 when the frame is grey (grade 0, or an empty slot) or cannot be read.
     */
    private static int borderGrade(BufferedImage img, int x, int y, int size) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int band = 1; band <= 3; band++) {
            for (int i = 0; i < size; i++) {
                int[][] pts = {{x + i, y - band}, {x + i, y + size - 1 + band}, {x - band, y + i}, {x + size - 1 + band, y + i}};
                for (int[] p : pts) {
                    if (p[0] < 0 || p[1] < 0 || p[0] >= img.getWidth() || p[1] >= img.getHeight()) continue;
                    int v = img.getRGB(p[0], p[1]);
                    int pr = (v >> 16) & 0xFF, pg = (v >> 8) & 0xFF, pb = v & 0xFF;
                    int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
                    if (max < 90 || max - min < 35) continue; // background or grey frame
                    r += pr; g += pg; b += pb; n++;
                }
            }
        }
        if (n < size) return -1;
        float[] hsb = Color.RGBtoHSB((int) (r / n), (int) (g / n), (int) (b / n), null);
        float hue = hsb[0] * 360;
        if (hue < 32 || hue >= 300) return 4;
        if (hue < 62) return 3;
        if (hue < 160) return 1;
        return hue < 270 ? 2 : -1;
    }

    // ---------------------------------------------------------------- matching

    /** Icons worth comparing for a cell: those with an item of the border's grade (all of them if unknown). */
    private List<Entry> candidatesFor(int grade) {
        if (grade < 0) return entries;
        List<Entry> c = entries.stream().filter(e -> e.names().stream().anyMatch(i -> i.grade() == grade))
                .collect(Collectors.toList());
        return c.isEmpty() ? entries : c;
    }

    /**
     * Closest icon to any of the cell's shifted features (every candidate is compared, no shortlist needed).
     * When several icons come out close, they are usually the same picture with a different tint (most elixirs
     * are one flask with a different liquid colour), so those are compared again on only the blocks where they
     * differ from each other.
     */
    private static Best bestOf(List<Feature> fs, List<Entry> candidates) {
        List<Best> all = new ArrayList<>();
        for (Entry e : candidates) {
            Best b = null;
            for (Feature f : fs) {
                double d = distance(e.feature(), f);
                if (b == null || d < b.distance()) b = new Best(e, f, d);
            }
            if (b != null) all.add(b);
        }
        if (all.isEmpty()) return null;
        all.sort(Comparator.comparingDouble(Best::distance));
        Best first = all.get(0);
        List<Best> close = new ArrayList<>();
        for (Best b : all) {
            if (close.size() == RERANK || b.distance() > first.distance() + RERANK_MARGIN) break;
            close.add(b);
        }
        if (close.size() < 2) return first;

        // block weight: how much the close candidates disagree there
        float[] spread = new float[G * G];
        for (int i = 0; i < G * G; i++) {
            for (int ch = 0; ch < 3; ch++) {
                float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
                for (Best b : close) {
                    float v = b.entry().feature().thumb()[i * 3 + ch];
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                spread[i] += max - min;
            }
        }
        Best winner = first;
        double winnerScore = Double.MAX_VALUE;
        for (Best b : close) {
            double s = weightedDistance(b.entry().feature(), b.cell(), spread);
            if (s < winnerScore) {
                winnerScore = s;
                winner = b;
            }
        }
        return winner;
    }

    /**
     * fs: the same screenshot cell at slightly different offsets, unshifted one first.
     * grade: grade read from the cell's border, or -1; when known, only items of that grade are considered.
     */
    private Match match(List<Feature> fs, int grade, int row, int column) {
        if (fs.get(0).grayStd() < EMPTY_STD) {
            return new Match(row, column, Status.EMPTY, null, Double.NaN, 0, 0, 0, List.of(), null);
        }

        List<Entry> candidates = candidatesFor(grade);
        Best found = bestOf(fs, candidates);
        Entry best = found == null ? null : found.entry();
        double bestDistance = found == null ? Double.NaN : found.distance();
        if (best == null) return new Match(row, column, Status.NOT_RECOGNISED, null, Double.NaN, 1, 1, 0, List.of(), null);

        // How much of the cell the match explains: its distance compared with an empty slot's. A wrong match
        // can still have a small distance when both pictures are mostly background (a small droplet vs. a
        // small pile of powder), but it is then hardly closer than an empty slot would be.
        double emptyRatio = bestDistance / Math.max(1e-6, distance(EMPTY_SLOT, found.cell()));
        double confidence = Math.max(0, Math.min(1, 1 - emptyRatio / NOT_RECOGNISED_RATIO));
        // nearest icon that is a different picture (not a tint or copy of the match)
        double runnerUp = Double.MAX_VALUE;
        for (Entry e : candidates) {
            if (e == best || distance(e.feature(), best.feature()) <= DIFFERENT_PICTURE) continue;
            runnerUp = Math.min(runnerUp, distance(e.feature(), found.cell()));
        }
        double lead = runnerUp == Double.MAX_VALUE ? 99 : runnerUp / Math.max(1e-6, bestDistance);
        // A small icon mostly hidden by a big number leaves little to compare, so even the right icon is hardly
        // closer than an empty slot (high emptyRatio). If it is still a close match and clearly closer than any
        // other picture, it is shown as UNSURE (check it) rather than not recognised. Items missing from the
        // data were measured at lead 0.97 - 1.12 and distance >= 0.054, so they stay not recognised.
        Status status = bestDistance > maxDistance || emptyRatio >= NOT_RECOGNISED_RATIO ? Status.NOT_RECOGNISED
                : emptyRatio >= UNSURE_RATIO ? Status.UNSURE : Status.RECOGNISED;
        if (status == Status.NOT_RECOGNISED && bestDistance <= HIDDEN_CLOSE && lead >= HIDDEN_LEAD) status = Status.UNSURE;

        // Report a name of the right grade; other items drawn with the same icon, or with an icon that is
        // the same picture, are alternatives
        List<ItemInfo> names = new ArrayList<>(best.names());
        String variant = baseName(best.info().name());
        for (Entry e : candidates) {
            if (e == best) continue;
            boolean samePicture = distance(e.feature(), best.feature()) <= SAME_ICON;
            // "X", "Immortal: X" and "[Event] X" share their art (apart from a glow) and can't be told apart
            boolean sameItem = variant != null && e.names().stream().anyMatch(i -> variant.equals(baseName(i.name())))
                    && distance(e.feature(), found.cell()) <= bestDistance + RERANK_MARGIN;
            if (samePicture || sameItem) names.addAll(e.names());
        }
        if (grade >= 0) names.removeIf(i -> i.grade() >= 0 && i.grade() != grade);
        if (names.isEmpty()) names.add(best.info());
        names = reported(names);
        if (names.get(0).name() == null) status = Status.NOT_RECOGNISED; // an icon no manifest names
        return new Match(row, column, status, names.get(0), bestDistance, emptyRatio, lead, confidence,
                names.subList(1, names.size()), null);
    }

    /** Applies the no-Immortals setting and the name overrides, and drops names that end up the same. */
    private List<ItemInfo> reported(List<ItemInfo> names) {
        Map<String, ItemInfo> out = new LinkedHashMap<>();
        for (ItemInfo i : names) {
            String n = i.name();
            if (n != null && ignoreImmortal) n = n.replaceFirst("Immortal:\\s*", "");
            if (n != null) n = nameOverrides.getOrDefault(n.toLowerCase(), n);
            out.putIfAbsent(n == null ? "#" + i.iconId() : n.toLowerCase(), new ItemInfo(i.iconId(), n, i.grade(), i.category()));
        }
        return new ArrayList<>(out.values());
    }

    /** Item name without the "[Event] " / "Immortal: " prefixes that mark variants of the same item; null for null. */
    private static String baseName(String name) {
        if (name == null) return null;
        return name.replaceFirst("^\\[Event\\]\\s*", "").replaceFirst("^Immortal:\\s*", "").toLowerCase();
    }

    /**
     * Mean colour difference (0..1) between a reference icon and a cell, over the blocks the cell's text leaves
     * visible. Compared as brightness plus two colour axes, with colour counted CHROMA_WEIGHT times: many icons
     * are the same picture with a different liquid tint.
     */
    private static double distance(Feature ref, Feature cell) {
        return weightedDistance(ref, cell, null);
    }

    /** {@link #distance} with an extra weight per block (null = all equal). */
    private static double weightedDistance(Feature ref, Feature cell, float[] blockWeight) {
        double sum = 0, weight = 0;
        float[] a = ref.thumb(), c = cell.thumb();
        for (int b = 0; b < G * G; b++) {
            float w = cell.weight()[b];
            if (w < MIN_BLOCK_WEIGHT) continue;
            if (blockWeight != null) w *= blockWeight[b];
            int o = b * 3;
            float dr = a[o] - c[o], dg = a[o + 1] - c[o + 1], db = a[o + 2] - c[o + 2];
            float lum = (dr + dg + db) / 3, rg = dr - dg, by = (dr + dg) / 2 - db;
            sum += w * (Math.abs(lum) + CHROMA_WEIGHT * (Math.abs(rg) + Math.abs(by)) / 2);
            weight += w;
        }
        return weight == 0 ? 1 : sum / (weight * 255 * (1 + CHROMA_WEIGHT));
    }

    // ---------------------------------------------------------------- features

    /**
     * Some icon files (e.g. the Draughts) have a red glow baked in as semi-transparent pixels that the game does
     * not draw in an item slot. Pixels below GLOW_ALPHA become transparent; the rest become opaque.
     */
    private static BufferedImage withoutGlow(BufferedImage src) {
        if (!src.getColorModel().hasAlpha()) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                out.setRGB(x, y, (p >>> 24) < GLOW_ALPHA ? 0 : p | 0xFF000000);
            }
        }
        return out;
    }

    /** Draws any image (including transparent icons) onto a 45 x 45 game-coloured cell. */
    private static BufferedImage toCell(BufferedImage src) {
        BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = cell.createGraphics();
        g.setColor(CELL_BACKGROUND);
        g.fillRect(0, 0, CELL, CELL);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, CELL, CELL, null);
        g.dispose();
        return cell;
    }

    /**
     * Colour thumbnail of the compared part of a cell. With maskText, the white quantity digits (and the dark
     * outline around them) in the lower part are left out, so the icon underneath can still be compared.
     */
    private static Feature featureOf(BufferedImage cell, boolean maskText) {
        int[] px = cell.getRGB(R0, R0, RS, RS, null, 0, RS);

        boolean[] hidden = new boolean[RS * RS];
        if (maskText) {
            for (int y = TEXT_TOP; y < RS; y++) {
                for (int x = 0; x < RS; x++) {
                    int p = px[y * RS + x];
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
                    if (max > 150 && max - min < 45) {
                        // text pixel: hide it and its outline
                        for (int yy = Math.max(TEXT_TOP, y - 1); yy <= Math.min(RS - 1, y + 1); yy++) {
                            for (int xx = Math.max(0, x - 1); xx <= Math.min(RS - 1, x + 1); xx++) hidden[yy * RS + xx] = true;
                        }
                    }
                }
            }
        }

        int bs = RS / G;
        float[] thumb = new float[G * G * 3];
        float[] weight = new float[G * G];
        double graySum = 0, graySq = 0;
        for (int by = 0; by < G; by++) {
            for (int bx = 0; bx < G; bx++) {
                float r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = by * bs; y < (by + 1) * bs; y++) {
                    for (int x = bx * bs; x < (bx + 1) * bs; x++) {
                        int p = px[y * RS + x];
                        double lum = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
                        graySum += lum;
                        graySq += lum * lum;
                        if (hidden[y * RS + x]) continue;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                int i = by * G + bx;
                weight[i] = n / (float) (bs * bs);
                if (n > 0) {
                    thumb[i * 3] = r / n;
                    thumb[i * 3 + 1] = g / n;
                    thumb[i * 3 + 2] = b / n;
                }
            }
        }
        double count = G * bs * G * bs;
        double mean = graySum / count;
        double std = Math.sqrt(Math.max(0, graySq / count - mean * mean));
        return new Feature(thumb, weight, std);
    }

    // ---------------------------------------------------------------- item data (tiny JSON reader)

    private static final List<String> ICON_KEYS = List.of("file", "icon", "icon_url", "iconpath", "icon_path");
    private static final List<String> NAME_KEYS = List.of("name", "itemname", "item_name", "displayname", "title", "name_en", "en");
    private static final List<String> CATEGORY_KEYS = List.of("maincategory", "main_category", "category");

    /** Collects every object holding a name and an icon path ({"name": "...", "file": "webp/00000613.webp"}). */
    @SuppressWarnings("unchecked")
    private static void collectNames(Object node, List<NamedIcon> out, int depth) {
        if (depth > 40) return;
        if (node instanceof List<?> list) {
            for (Object o : list) collectNames(o, out, depth + 1);
        } else if (node instanceof Map<?, ?> raw) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
                map.put(e.getKey().toLowerCase(), e.getValue());
            }
            NamedIcon info = infoOf(k -> map.get(k) == null ? null : String.valueOf(map.get(k)));
            if (info != null) out.add(info);
            for (Object v : map.values()) collectNames(v, out, depth + 1);
        }
    }

    /** Builds a name row from one record (a JSON object or CSV row), or null if it has no name or icon. */
    private static NamedIcon infoOf(java.util.function.Function<String, String> field) {
        Integer icon = null;
        List<String> keys = List.of();
        for (String k : ICON_KEYS) {
            String v = field.apply(k);
            if (v != null && (icon = iconNumber(v.trim())) != null) {
                keys = iconKeys(v.trim());
                break;
            }
        }
        String name = null;
        for (String k : NAME_KEYS) {
            String v = field.apply(k);
            if (v != null && !v.isBlank()) { name = v.trim(); break; }
        }
        if (icon == null || name == null) return null;
        int grade = -1;
        String g = field.apply("grade");
        if (g != null) {
            try { grade = (int) Double.parseDouble(g.trim()); } catch (NumberFormatException ignored) { }
        }
        String category = null;
        for (String k : CATEGORY_KEYS) {
            String v = field.apply(k);
            if (v != null && !v.isBlank()) { category = v.trim(); break; }
        }
        return new NamedIcon(keys, new ItemInfo(icon, name, grade, category));
    }

    /** CSV with a header row naming an icon column and a name column. */
    private static void collectNamesCsv(String text, List<NamedIcon> out) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',' || c == ';' || c == '\t') { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        if (rows.isEmpty()) return;

        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) header.putIfAbsent(rows.get(0).get(i).trim().toLowerCase(), i);
        for (int r = 1; r < rows.size(); r++) {
            List<String> cols = rows.get(r);
            NamedIcon info = infoOf(k -> {
                Integer i = header.get(k);
                return i == null || i >= cols.size() ? null : cols.get(i);
            });
            if (info != null) out.add(info);
        }
    }

    private static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            ws();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new HashMap<>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') { i++; return map; }
            while (true) {
                ws();
                String key = string();
                ws();
                i++; // :
                map.put(key, value());
                ws();
                if (s.charAt(i++) == '}') return map;
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(value());
                ws();
                if (s.charAt(i++) == ']') return list;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e); // \" \\ \/
                }
            }
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
        }
    }
}