import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the scanner against a screenshot whose contents are known.
 *
 * Usage: java ScannerTest <screenshot.png> <expected.txt> [icons_and_data folder] [glyph file to use instead]
 *
 * expected.txt has one slot per line: "row column quantity name", e.g.
 *     1 1 213.3K Elixir of Fury
 *     1 5 351 Immortal: Fury Draught|Fury Draught
 * Several acceptable names are separated by "|". Use "?" as the quantity or the name to skip checking it.
 */
public class ScannerTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java ScannerTest <screenshot.png> <expected.txt> [icons_and_data folder]");
            return;
        }
        Path data = args.length > 2 ? Path.of(args[2]) : Path.of("../icons_and_data");

        Map<String, String[]> expected = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8)) {
            line = line.replace("﻿", "").trim(); // Notepad / PowerShell may start the file with a BOM
            String[] p = line.split("\\s+", 4);
            if (p.length == 4 && !line.startsWith("#")) expected.put(p[0] + "," + p[1], new String[]{p[2], p[3]});
        }

        ItemIconRecognizer recognizer = new ItemIconRecognizer(data);
        recognizer.setIgnoreImmortal(true); // same setting as Main: the storage holds no Immortal items
        if (args.length > 3) recognizer.setQuantityReader(QuantityReader.load(Path.of(args[3])));
        BufferedImage image = ImageIO.read(Path.of(args[0]).toFile());
        ItemIconRecognizer.GridLayout layout =
                ItemIconRecognizer.GridLayout.detect(image, ItemIconRecognizer.GridLayout.WAREHOUSE_PANEL);
        List<ItemIconRecognizer.Match> results = recognizer.recognise(image, layout, false);

        int names = 0, nameTotal = 0, listed = 0, qty = 0, qtyTotal = 0, extra = 0;
        for (ItemIconRecognizer.Match m : results) {
            String[] want = expected.remove(m.row() + "," + m.column());
            String gotName = m.recognised() ? m.item().name() : "(not recognised)";
            String gotQty = m.quantity() == null ? "?" : m.quantity().text();
            if (want == null) {
                extra++;
                System.out.printf("EXTRA  %d,%d  %s %s (not in expected file)%n", m.row(), m.column(), gotQty, gotName);
                continue;
            }
            if (!want[1].equals("?")) { // "?" = name not known: only the quantity is checked
                nameTotal++;
                List<String> wanted = List.of(want[1].split("\\|"));
                if (wanted.contains(gotName)) names++;
                else if (m.alternatives().stream().anyMatch(a -> wanted.contains(a.name()))) {
                    names++;
                    listed++;
                    System.out.printf("ALT    %d,%d  \"%s\" given as an alternative to \"%s\" (same picture)%n",
                            m.row(), m.column(), want[1], gotName);
                } else System.out.printf("NAME   %d,%d  got \"%s\"  want \"%s\"%n", m.row(), m.column(), gotName, want[1]);
            }
            if (!want[0].equals("?")) {
                qtyTotal++;
                if (want[0].equals(gotQty)) qty++;
                else System.out.printf("QTY    %d,%d  got %s  want %s%n", m.row(), m.column(), gotQty, want[0]);
            }
        }
        for (Map.Entry<String, String[]> e : expected.entrySet()) {
            System.out.printf("MISSING %s  %s (expected but the slot was read as empty)%n", e.getKey(), e.getValue()[1]);
            nameTotal++;
        }
        System.out.printf("%nNames: %d / %d correct%s.  Quantities: %d / %d correct.%s%n", names, nameTotal,
                listed > 0 ? " (" + listed + " only as an alternative)" : "", qty, qtyTotal,
                extra > 0 ? "  " + extra + " slot(s) not in expected file." : "");
    }
}
