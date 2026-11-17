import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the stack size printed in the bottom-right corner of an item cell ("17351", "288.3K").
 *
 * The game draws the number in one small font, so each character is recognised by comparing it with example
 * glyphs cut from labelled screenshots (nearest neighbour). The examples live in a text file, see
 * {@link #load}/{@link #save}; {@link #main} builds that file from a screenshot and a list of the numbers in it.
 *
 * Coordinates are relative to the icon box the glyph file was made at: 34 px for the standard UI size of the
 * market warehouse panel. Other in-game UI sizes draw the font at another pixel size, so each needs its own glyph
 * file, made at that size (see {@link #box}). A cell of another size is scaled to the reader's size first,
 * which is unreliable: the scaled digits blur together.
 */
public final class QuantityReader {

    /** A read quantity. value is the number it stands for ("288.3K" -> 288300), or -1 if it could not be read. */
    public record Quantity(String text, long value) {
        /** True when the game rounded the number ("288.3K"), so value is only accurate to the shown digits. */
        public boolean approximate() { return text.endsWith("K") || text.endsWith("M"); }
    }

    // ---------------------------------------------------------------- geometry

    // Measured on the 34 px icon box (standard UI size); other sizes scale these.
    private static final int BOX = 34;
    private final int box;                // icon box size (px) the glyphs are for
    private final int top, h;             // text rows below the top of the icon box (glyph files with fewer rows are padded)
    private final int left, right, w;     // text may start a little left of the icon box
    private final int aboveText;          // top rows of the band that are never text
    private final int dotTop, dotSize;    // a decimal point: starts at or below row dotTop, at most dotSize px each way
    private final int minSplit;           // a piece this wide may be two touching digits
    private final int maxGap;             // more empty columns than this end the number
    private static final double SPLIT_GAIN = 0.8;     // a split must be this much better than one wide glyph ("K")
    // The digits are pure grey-white. Pale icon art (wood, stone) that reaches into the text area is beige or
    // tinted, so only pixels whose colour channels differ by at most MAX_TINT count as text, and the top
    // ABOVE_TEXT rows, which big icons cover but digits never reach, are ignored. Where a digit is drawn over
    // coloured icon art its edges blend and are lost, so a character only needs MIN_GLYPH_HEIGHT rows.
    // (Measured on two panels: wood 58 -> 84, materials 69 -> 86 of 90 right, elixirs unchanged at 63.)
    private static final int MAX_TINT = 10;
    private static final int MIN_GLYPH_HEIGHT = 4;
    private final Map<Character, List<float[][]>> glyphs = new TreeMap<>();

    /** Empty reader for the standard UI size (reads nothing until glyphs are added or loaded). */
    public QuantityReader() { this(BOX); }

    /** Empty reader for slots whose icon box is this many pixels (another in-game UI size). */
    public QuantityReader(int box) {
        this.box = box;
        double f = box / (double) BOX;
        top = (int) Math.round(21 * f);
        h = (int) Math.round(13 * f);
        left = (int) Math.round(-6 * f);
        right = (int) Math.round(34 * f);
        w = right - left;
        aboveText = (int) Math.round(2 * f);
        dotTop = (int) Math.round(8 * f);
        dotSize = Math.max(3, (int) Math.round(3 * f));
        minSplit = (int) Math.round(7 * f);
        maxGap = Math.max(3, (int) Math.round(3 * f));
    }

    /** Icon box size (px) this reader's glyphs were made at: 34 for the standard UI size. */
    public int box() { return box; }

    /** Number of example glyphs known. */
    public int size() { return glyphs.values().stream().mapToInt(List::size).sum(); }

    // ---------------------------------------------------------------- reading

    /** Reads the quantity of the cell whose icon box is at (x, y) with the given size in the screenshot. */
    public Quantity read(BufferedImage screenshot, int x, int y, int size) {
        float[][] band = band(screenshot, x, y, size);
        if (band == null || glyphs.isEmpty()) return new Quantity("", -1);
        List<Seg> segs = segments(band);
        while (!segs.isEmpty() && isDot(segs.get(0))) segs.remove(0);

        // The game writes either plain digits ("17351") or digits, a dot, one digit and K/M ("288.3K"). Read the
        // whole number at once: try every way of grouping the pieces into characters that fits one of those
        // forms (a wide "M" often falls apart into two or three pieces) and keep the grouping that matches the
        // example glyphs best.
        Parse best = parse(segs, 0, 0, 0, new HashMap<>());
        if (best != null) {
            String t = best.text();
            return new Quantity(t, valueOf(t));
        }

        // no grouping fits a known form: read piece by piece (the result will be rejected as not a number)
        int dot = -1;
        for (int i = 0; i < segs.size() - 1; i++) if (isDot(segs.get(i))) { dot = i; break; }
        StringBuilder text = new StringBuilder();
        if (dot < 0) {
            for (Seg s : segs) text.append(digits(s));
        } else {
            for (int i = 0; i < dot; i++) text.append(digits(segs.get(i)));
            text.append('.');
            Seg tenth = segs.get(dot + 1);
            text.append(classify(tenth.ink(), tenth.x0(), tenth.x1(), DIGITS)[0]);
            if (segs.size() > dot + 2) {
                Seg suffix = merge(segs.subList(dot + 2, segs.size()));
                text.append(classify(suffix.ink(), suffix.x0(), suffix.x1(), SUFFIXES)[0]);
            }
        }
        String t = text.toString();
        return new Quantity(t, valueOf(t));
    }

    private static final String DIGITS = "0123456789", SUFFIXES = "KM";
    private static final int MAX_PIECES = 3;   // one character may have fallen apart into this many pieces
    private static final double HIDDEN_DOT_PENALTY = 0.3; // extra cost for assuming a dot hidden in a digit
    private static final double CHAR_COST = 1.0;          // cost of every character read (discourages needless splits)

    /** What reading one character costs: its total mismatch (see classify) plus CHAR_COST. */
    private static double cost(Object[] classified) {
        return (double) classified[2] + CHAR_COST;
    }

    /** The best reading of the pieces from 'from' on: its text and how badly it matches (lower is better). */
    private record Parse(String text, double cost) { }

    /**
     * Best grouping of segs[from..] into characters, given what has been read so far. phase 0: the whole number
     * (count digits so far), 1: just read the dot, 2: read the digit after the dot, 3: read K/M (must be the end).
     * null if nothing fits. A character costs its total mismatch over every column it was compared on (its piece
     * and the example glyph) plus CHAR_COST, so readings that group the pieces differently are compared fairly
     * and a shape is only split into several characters when that really fits better.
     */
    private Parse parse(List<Seg> segs, int from, int phase, int count, Map<String, Parse> memo) {
        if (from == segs.size()) {
            return (phase == 0 && count >= 1) || phase == 3 ? new Parse("", 0) : null;
        }
        if (phase == 3) return null;
        String key = from + "," + phase + "," + count;
        if (memo.containsKey(key)) return memo.get(key);

        Parse best = null;
        for (int to = from + 1; to <= Math.min(segs.size(), from + MAX_PIECES); to++) {
            Seg s = merge(segs.subList(from, to));
            List<Object[]> options = new ArrayList<>(); // {text, cost, next phase, next count}
            if (phase == 0) {
                Object[] d = classify(s.ink(), s.x0(), s.x1(), DIGITS);
                options.add(new Object[]{String.valueOf(d[0]), cost(d), 0, count + 1});
                if (s.x1() - s.x0() >= minSplit) {
                    for (int cut = s.x0() + 2; cut <= s.x1() - 2; cut++) {
                        Object[] l = classify(s.ink(), s.x0(), cut, DIGITS), r = classify(s.ink(), cut, s.x1(), DIGITS);
                        options.add(new Object[]{"" + l[0] + r[0], (cost(l) + cost(r)) / SPLIT_GAIN, 0, count + 2});
                    }
                }
                if (to == from + 1 && isDot(s) && count >= 1 && count <= 3) options.add(new Object[]{".", CHAR_COST, 1, count});
                // the game squeezes the digit after the dot over it ("2.2M", "307.4K"), so the dot can be hidden
                // inside that digit's piece: read it as dot + digit when K/M follows (checked by phase 2 -> 3)
                if (count >= 1 && count <= 3) {
                    options.add(new Object[]{"." + d[0], cost(d) + HIDDEN_DOT_PENALTY, 2, count});
                }
            } else if (phase == 1) {
                Object[] d = classify(s.ink(), s.x0(), s.x1(), DIGITS);
                options.add(new Object[]{String.valueOf(d[0]), cost(d), 2, count});
            } else {
                Object[] k = classify(s.ink(), s.x0(), s.x1(), SUFFIXES);
                options.add(new Object[]{String.valueOf(k[0]), cost(k), 3, count});
            }
            for (Object[] o : options) {
                if ((int) o[3] > 5 || ((String) o[0]).contains("?") || (double) o[1] > 1e9) continue; // no example fits
                Parse rest = parse(segs, to, (int) o[2], (int) o[3], memo);
                if (rest == null) continue;
                double cost = (double) o[1] + rest.cost();
                if (best == null || cost < best.cost()) best = new Parse(o[0] + rest.text(), cost);
            }
        }
        memo.put(key, best);
        return best;
    }

    /** One digit, or two if the segment is wide enough to be two touching digits and splitting fits clearly better. */
    private String digits(Seg s) {
        Object[] one = classify(s.ink(), s.x0(), s.x1(), DIGITS);
        String part = String.valueOf(one[0]);
        if (s.x1() - s.x0() >= minSplit) {
            double best = (double) one[1] * SPLIT_GAIN;
            for (int cut = s.x0() + 2; cut <= s.x1() - 2; cut++) {
                Object[] l = classify(s.ink(), s.x0(), cut, DIGITS), r = classify(s.ink(), cut, s.x1(), DIGITS);
                double d = ((double) l[1] * (cut - s.x0()) + (double) r[1] * (s.x1() - cut)) / (s.x1() - s.x0());
                if (d < best) {
                    best = d;
                    part = "" + l[0] + r[0];
                }
            }
        }
        return part;
    }

    /** A decimal point: a small blob on the bottom rows (the top rows, above the digits, are ignored). */
    private boolean isDot(Seg s) {
        int first = h, bottom = -1;
        for (int y = Math.max(2, aboveText); y < h; y++) {
            for (int x = s.x0(); x < s.x1(); x++) {
                if (s.ink()[y][x] > STRONG) {
                    first = Math.min(first, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        return bottom >= 0 && bottom - first + 1 <= dotSize && first >= dotTop && s.x1() - s.x0() <= dotSize;
    }

    /**
     * "17351" -> 17351, "288.3K" -> 288300, "1.2M" -> 1200000; -1 if it is not a number the game would show.
     * The game writes up to 99999 in full, 100.0K - 999.9K in thousands and 1.0M and up in millions, so a read
     * such as "139718" (really "139.1K") or "2.0K" (really "2.0M") must be a misread and is rejected.
     */
    static long valueOf(String t) {
        if (!t.matches("[1-9]\\d{0,4}|0|[1-9]\\d{2}\\.\\d K|[1-9]\\d{0,2}\\.\\d M".replace(" ", ""))) return -1;
        long mult = t.endsWith("K") ? 1_000 : t.endsWith("M") ? 1_000_000 : 1;
        String n = mult == 1 ? t : t.substring(0, t.length() - 1);
        return Math.round(Double.parseDouble(n) * mult);
    }

    /**
     * Nearest example glyph among the allowed characters to columns [x0, x1) of the band:
     * {char, mean difference per column, total difference over all columns compared}.
     */
    private Object[] classify(float[][] b, int x0, int x1, String allowed) {
        char best = '?';
        double bestD = Double.MAX_VALUE, bestTotal = Double.MAX_VALUE;
        for (Map.Entry<Character, List<float[][]>> e : glyphs.entrySet()) {
            if (allowed.indexOf(e.getKey()) < 0) continue;
            for (float[][] t : e.getValue()) {
                double[] d = difference(b, x0, x1, t);
                if (d[0] < bestD) {
                    bestD = d[0];
                    bestTotal = d[0] * d[1];
                    best = e.getKey();
                }
            }
        }
        return new Object[]{best, bestD, bestTotal};
    }

    /**
     * Mean pixel difference per column between columns [x0, x1) and one example glyph at its best alignment
     * (+-1 px), and the number of columns that was compared over (the piece and the example together).
     */
    private double[] difference(float[][] b, int x0, int x1, float[][] t) {
        final int H = h;
        int w = x1 - x0, tw = t[0].length;
        if (Math.abs(tw - w) > 1) return new double[]{Double.MAX_VALUE, 0};
        double best = Double.MAX_VALUE;
        int span = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = Math.min(0, w - tw); dx <= Math.max(0, w - tw); dx++) {
                int lo = Math.min(x0, x0 + dx), hi = Math.max(x1, x0 + dx + tw);
                double d = 0;
                for (int y = 0; y < H; y++) {
                    for (int x = lo; x < hi; x++) {
                        float iv = x >= x0 && x < x1 ? b[y][x] : 0;
                        int ty = y - dy, tx = x - x0 - dx;
                        float tv = ty >= 0 && ty < H && tx >= 0 && tx < tw ? t[ty][tx] : 0;
                        d += Math.abs(iv - tv);
                    }
                }
                if (d / (hi - lo) < best) {
                    best = d / (hi - lo);
                    span = hi - lo;
                }
            }
        }
        return new double[]{best, span};
    }

    // ---------------------------------------------------------------- text extraction

    /** "Ink" (0..1) of the text area: bright, colourless pixels with the dark outline the game draws round text. */
    private float[][] band(BufferedImage img, int x, int y, int size) {
        final int H = h, W = w, TOP = top, LEFT = left, RIGHT = right;
        BufferedImage src = img;
        int bx = x, by = y;
        if (Math.abs(size - box) > 1) {
            // no glyphs for this UI size: scale the cell and its surroundings to the reader's size (unreliable)
            int pad = 8;
            int sx = Math.max(0, x - pad * size / box), sy = Math.max(0, y - pad * size / box);
            int sw = Math.min(img.getWidth() - sx, size + 2 * pad * size / box), sh = Math.min(img.getHeight() - sy, size + 2 * pad * size / box);
            double k = (double) box / size;
            src = new BufferedImage((int) Math.round(sw * k), (int) Math.round(sh * k), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = src.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img.getSubimage(sx, sy, sw, sh), 0, 0, src.getWidth(), src.getHeight(), null);
            g.dispose();
            bx = (int) Math.round((x - sx) * k);
            by = (int) Math.round((y - sy) * k);
        }
        if (bx < 0 || by + TOP < 0 || bx + RIGHT + 2 > src.getWidth() || by + TOP + H + 2 > src.getHeight()) return null;
        float[][] b = new float[H][W];
        for (int row = 0; row < H; row++) {
            for (int col = 0; col < W; col++) {
                int px = bx + LEFT + col, py = by + TOP + row;
                if (px < 2) continue; // the capture can cut off the left of the first column
                if (row < aboveText) continue; // big icons reach down here; no digit ever does
                float v = ink(src.getRGB(px, py));
                if (v > 0 && !nearDark(src, px, py)) v = 0; // bright icon detail, not text
                b[row][col] = v;
            }
        }
        return b;
    }

    private static float ink(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min > MAX_TINT) return 0;
        return Math.max(0, Math.min(1, (min - 60) / 150f));
    }

    private static boolean nearDark(BufferedImage img, int px, int py) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int p = img.getRGB(px + dx, py + dy);
                if (Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF)) < 45) return true;
            }
        }
        return false;
    }

    private static final float STRONG = 0.45f; // ink above this is part of a stroke, weaker ink is its soft edge

    /**
     * One character: columns [x0, x1) of the band, and ink holding only this character's pixels (the band is
     * shared, and a character can reach over its neighbour: the game tucks the "4" of ".4K" over the dot).
     */
    private record Seg(int x0, int x1, float[][] ink) { }

    /**
     * The characters, left to right: runs of columns holding ink, read from the right edge leftwards until a
     * gap or something that does not look like a character. (Known weakness: when the game tucks the digit after
     * the decimal point over the dot, as in some "307.4K", the two come out as one run and the number is misread;
     * valueOf() then rejects it, so it is reported as unreadable rather than counted wrong.)
     */
    private List<Seg> segments(float[][] b) {
        float[] col = new float[w];
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) if (b[y][x] > STRONG) col[x]++;
        List<Seg> segs = new ArrayList<>();
        int x = w - 1, gap = 0;
        while (x >= 0 && col[x] == 0) x--;
        while (x >= 0) {
            if (col[x] == 0) {
                if (++gap > maxGap) break;
                x--;
                continue;
            }
            gap = 0;
            int end = x + 1;
            while (x >= 0 && col[x] > 0) x--;
            segs.add(0, new Seg(x + 1, end, b));
        }
        // Pieces that don't look like a character are kept inside the number (the slanted middle of an "M"
        // falls apart into short pieces), but at its left end they are icon detail, not part of the number.
        while (!segs.isEmpty() && !glyphLike(segs.get(0))) segs.remove(0);
        return segs;
    }

    /** Characters are strong strokes, either tall (digits, K) or a small blob at the bottom (decimal point). */
    private boolean glyphLike(Seg s) {
        float max = 0;
        int first = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = s.x0(); x < s.x1(); x++) {
                max = Math.max(max, s.ink()[y][x]);
                if (s.ink()[y][x] > STRONG) {
                    first = Math.min(first, y);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        if (max < 0.75f) return false;
        int height = bottom - first + 1;
        return height >= MIN_GLYPH_HEIGHT || (height <= dotSize && first >= dotTop);
    }

    /** Several characters read as one (a suffix letter that came apart into pieces). */
    private Seg merge(List<Seg> parts) {
        float[][] ink = new float[h][w];
        int x0 = w, x1 = 0;
        for (Seg s : parts) {
            x0 = Math.min(x0, s.x0());
            x1 = Math.max(x1, s.x1());
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) ink[y][x] = Math.max(ink[y][x], s.ink()[y][x]);
        }
        return new Seg(x0, x1, ink);
    }

    // ---------------------------------------------------------------- training and storage

    /**
     * Adds the characters of one cell whose number is known. Returns false (adding nothing) if the cell does not
     * split into exactly that many characters.
     */
    public boolean learn(BufferedImage screenshot, int x, int y, int size, String text) {
        float[][] b = band(screenshot, x, y, size);
        if (b == null) return false;
        List<Seg> segs = segments(b);
        int n = text.length();
        if (segs.size() > n && segs.size() - n < MAX_PIECES && SUFFIXES.indexOf(text.charAt(n - 1)) >= 0) {
            // a K or M that fell apart into pieces: learn it as one character
            List<Seg> joined = new ArrayList<>(segs.subList(0, n - 1));
            joined.add(merge(segs.subList(n - 1, segs.size())));
            segs = joined;
        }
        if (segs.size() == n - 1 && text.contains(".")) {
            // the dot is hidden in the digit after it: learn that digit with the dot as part of it
            text = text.replace(".", "");
            n--;
        }
        if (segs.size() != n) return false;
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            float[][] g = new float[h][s.x1() - s.x0()];
            for (int row = 0; row < h; row++) System.arraycopy(s.ink()[row], s.x0(), g[row], 0, s.x1() - s.x0());
            glyphs.computeIfAbsent(text.charAt(i), k -> new ArrayList<>()).add(g);
        }
        return true;
    }

    /**
     * Glyph file: one example per line, "<char> <row>/<row>/..." with each pixel as a digit 0-9 (ink level).
     */
    public static QuantityReader load(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int box = BOX; // a "#box <px>" line gives the UI size the glyphs were made at (none: the standard 34)
        for (String line : lines) if (line.startsWith("#box ")) box = Integer.parseInt(line.substring(5).trim());
        QuantityReader r = new QuantityReader(box);
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] rows = line.substring(2).split("/");
            float[][] g = new float[r.h][rows[0].length()];
            for (int y = 0; y < r.h && y < rows.length; y++) {
                for (int x = 0; x < rows[y].length(); x++) g[y][x] = (rows[y].charAt(x) - '0') / 9f;
            }
            r.glyphs.computeIfAbsent(line.charAt(0), k -> new ArrayList<>()).add(g);
        }
        return r;
    }

    public void save(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Quantity font glyphs for QuantityReader (" + box + " px icon box). <char> <rows separated by />, ink 0-9.");
        if (box != BOX) lines.add("#box " + box);
        for (Map.Entry<Character, List<float[][]>> e : glyphs.entrySet()) {
            for (float[][] g : e.getValue()) {
                StringBuilder sb = new StringBuilder().append(e.getKey()).append(' ');
                for (int y = 0; y < h; y++) {
                    if (y > 0) sb.append('/');
                    for (float v : g[y]) sb.append((char) ('0' + Math.round(v * 9)));
                }
                lines.add(sb.toString());
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * Builds or extends a glyph file from a warehouse-panel screenshot and the numbers shown in it.
     * Usage: java QuantityReader <screenshot.png> <labels.txt> <glyphs.txt>
     * labels.txt: one cell per line, "row column number" (1-based), e.g. "1 6 27854" or "1 1 288.3K";
     * anything after the number (an item name, as in ScannerTest's expected files) is ignored, and so are
     * lines starting with # or with ? as the number. If glyphs.txt exists, the new examples are added to it.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.out.println("Usage: java QuantityReader <screenshot.png> <labels.txt> <glyphs.txt>");
            return;
        }
        Path out = Path.of(args[2]);
        BufferedImage img = ImageIO.read(Path.of(args[0]).toFile());
        ItemIconRecognizer.GridLayout layout =
                ItemIconRecognizer.GridLayout.detect(img, ItemIconRecognizer.GridLayout.WAREHOUSE_PANEL);
        // each in-game UI size gets its own glyph file, made at that size
        QuantityReader r = Files.exists(out) ? load(out) : new QuantityReader(layout.cellSize());
        if (Math.abs(r.box() - layout.cellSize()) > 1) {
            System.out.println("This screenshot's slots are " + layout.cellSize() + " px but " + out + " is for "
                    + r.box() + " px slots (another UI size). Use a glyph file for this size, e.g. quantity-glyphs-"
                    + layout.cellSize() + ".txt");
            return;
        }
        int added = 0, skipped = 0;
        for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            line = line.replace("﻿", "").trim(); // Notepad / PowerShell may start the file with a BOM
            String[] p = line.split("\\s+", 4);
            if (p.length < 3 || line.startsWith("#") || p[2].equals("?")) continue;
            int row = Integer.parseInt(p[0]) - 1, col = Integer.parseInt(p[1]) - 1;
            int x = (int) Math.round(layout.firstX() + col * layout.pitch());
            int y = (int) Math.round(layout.firstY() + row * layout.pitchY());
            if (r.learn(img, x, y, layout.cellSize(), p[2])) added++;
            else {
                skipped++;
                System.out.println("skipped (could not split into characters): " + line);
            }
        }
        r.save(out);
        System.out.println("added " + added + " cells, skipped " + skipped + "; " + r.size() + " glyphs in " + out);
    }
}
