/// Print a per-module / per-class JaCoCo coverage summary for the whole build.
///
/// Reads the aggregated report that `jacoco-report-aggregation` writes at
/// `build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml` (produced by
/// `./gradlew testCodeCoverageReport`, or just run `just coverage`, which does both). A single-file
/// Java program — run it directly with `java scripts/CoverageSummary.java` (no build step); it
/// needs only a JDK, which the dev image and CI already provide.
///
/// The aggregated view credits a class for coverage from ANY module's tests, so an adapter that is
/// exercised only by :app's end-to-end pipeline tests shows as covered here — unlike the per-module
/// floors enforced by `just build`, which see only each module's own tests.
///
/// Usage:
///   java scripts/CoverageSummary.java                # print the table, always exit 0
///   java scripts/CoverageSummary.java --min 80       # also fail (exit 1) if total line% < 80
///   java scripts/CoverageSummary.java path/to/report.xml [--min N]
///
/// Exit status: 0 if fine (or no threshold given), 1 if below --min, 2 if the report is missing.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class CoverageSummary {

    private static final String DEFAULT_REPORT =
            "build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml";

    /** The base package whose first sub-segment names the module a class belongs to. */
    private static final String BASE = "io/github/p4suta/despeckle/";

    /** Module display order; "app" collects the cli front end and Main. */
    private static final List<String> MODULES =
            List.of("domain", "port", "application", "infrastructure", "observability", "app");

    /** A running (missed, covered) tally for one JaCoCo counter type. */
    static final class Tally {
        long lineMissed;
        long lineCovered;
        long branchMissed;
        long branchCovered;

        long lineTotal() {
            return lineMissed + lineCovered;
        }

        long branchTotal() {
            return branchMissed + branchCovered;
        }

        double linePct() {
            return lineTotal() == 0 ? 100.0 : 100.0 * lineCovered / lineTotal();
        }

        double branchPct() {
            return branchTotal() == 0 ? 100.0 : 100.0 * branchCovered / branchTotal();
        }

        void add(Tally other) {
            lineMissed += other.lineMissed;
            lineCovered += other.lineCovered;
            branchMissed += other.branchMissed;
            branchCovered += other.branchCovered;
        }
    }

    /** One class's coverage, with the module it was bucketed into. */
    record ClassCoverage(String module, String name, Tally tally) {}

    public static void main(String[] args) throws Exception {
        Path report = Path.of(DEFAULT_REPORT);
        double min = -1;
        for (int i = 0; i < args.length; i++) {
            if ("--min".equals(args[i]) && i + 1 < args.length) {
                min = Double.parseDouble(args[++i]);
            } else if (!args[i].startsWith("--")) {
                report = Path.of(args[i]);
            }
        }

        if (!Files.isReadable(report)) {
            System.err.println("coverage report not found: " + report);
            System.err.println("  run `just coverage` (or `./gradlew testCodeCoverageReport`) first.");
            System.exit(2);
        }

        List<ClassCoverage> classes = parse(report);
        print(classes, report, min);
    }

    private static List<ClassCoverage> parse(Path report) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // JaCoCo's XML declares a DOCTYPE; never fetch the external DTD (offline + faster).
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/validation", false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));

        NodeList classNodes = builder.parse(report.toFile()).getElementsByTagName("class");
        List<ClassCoverage> out = new ArrayList<>();
        for (int i = 0; i < classNodes.getLength(); i++) {
            Element cls = (Element) classNodes.item(i);
            String name = cls.getAttribute("name");
            Tally tally = new Tally();
            // Only DIRECT-child counters are the class aggregate; counters nested in <method> are
            // per-method and must not be double-counted.
            for (Node child = cls.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE && "counter".equals(child.getNodeName())) {
                    Element counter = (Element) child;
                    long missed = Long.parseLong(counter.getAttribute("missed"));
                    long covered = Long.parseLong(counter.getAttribute("covered"));
                    switch (counter.getAttribute("type")) {
                        case "LINE" -> {
                            tally.lineMissed = missed;
                            tally.lineCovered = covered;
                        }
                        case "BRANCH" -> {
                            tally.branchMissed = missed;
                            tally.branchCovered = covered;
                        }
                        default -> {
                            // INSTRUCTION/METHOD/CLASS/COMPLEXITY counters are not summarized here.
                        }
                    }
                }
            }
            out.add(new ClassCoverage(moduleOf(name), simpleName(name), tally));
        }
        return out;
    }

    /** Buckets a JaCoCo class name (slash-separated) into one of {@link #MODULES}. */
    private static String moduleOf(String className) {
        if (!className.startsWith(BASE)) {
            return "app";
        }
        String rest = className.substring(BASE.length());
        int slash = rest.indexOf('/');
        String head = slash < 0 ? "" : rest.substring(0, slash);
        return switch (head) {
            case "domain", "port", "application", "infrastructure", "observability" -> head;
            // cli front end, and Main (which has no sub-package) both live in :app.
            default -> "app";
        };
    }

    private static String simpleName(String className) {
        int slash = className.lastIndexOf('/');
        return slash < 0 ? className : className.substring(slash + 1);
    }

    private static void print(List<ClassCoverage> classes, Path report, double min) {
        Map<String, Tally> byModule = new LinkedHashMap<>();
        for (String m : MODULES) {
            byModule.put(m, new Tally());
        }
        for (ClassCoverage c : classes) {
            byModule.computeIfAbsent(c.module(), k -> new Tally()).add(c.tally());
        }

        System.out.println();
        System.out.println("Coverage summary  (aggregated across all modules — " + report + ")");
        System.out.println("─".repeat(64));
        System.out.printf("%-16s %18s %18s%n", "module", "line", "branch");
        System.out.println("─".repeat(64));
        Tally total = new Tally();
        for (Map.Entry<String, Tally> e : byModule.entrySet()) {
            Tally t = e.getValue();
            total.add(t);
            if (t.lineTotal() == 0) {
                System.out.printf("%-16s %18s %18s%n", e.getKey(), "—", "—");
            } else {
                System.out.printf(
                        "%-16s %6.1f%% %10s %6.1f%% %10s%n",
                        e.getKey(),
                        t.linePct(),
                        "(" + t.lineCovered + "/" + t.lineTotal() + ")",
                        t.branchPct(),
                        "(" + t.branchCovered + "/" + t.branchTotal() + ")");
            }
        }
        System.out.println("─".repeat(64));
        System.out.printf(
                "%-16s %6.1f%% %10s %6.1f%% %10s%n",
                "TOTAL",
                total.linePct(),
                "(" + total.lineCovered + "/" + total.lineTotal() + ")",
                total.branchPct(),
                "(" + total.branchCovered + "/" + total.branchTotal() + ")");

        // Lowlights: the thinnest-covered classes, so it is obvious where coverage is missing.
        List<ClassCoverage> thin = new ArrayList<>();
        for (ClassCoverage c : classes) {
            if (c.tally().lineTotal() > 0 && c.tally().linePct() < 75.0) {
                thin.add(c);
            }
        }
        thin.sort((a, b) -> Double.compare(a.tally().linePct(), b.tally().linePct()));
        if (!thin.isEmpty()) {
            System.out.println();
            System.out.println("Thinnest classes (line < 75%):");
            for (ClassCoverage c : thin) {
                System.out.printf(
                        "  %5.1f%%  %-28s  %s%n",
                        c.tally().linePct(), c.name(), c.module());
            }
        }

        System.out.println();
        System.out.println("Browse: " + report.toAbsolutePath().getParent().resolve("index.html"));

        if (min >= 0 && total.linePct() < min) {
            System.err.printf("%nFAIL: total line coverage %.1f%% is below --min %.1f%%%n", total.linePct(), min);
            System.exit(1);
        }
    }
}
