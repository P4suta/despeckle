/// Check that the dev image's pinned tools are still the latest upstream release.
///
/// Reads each `ARG <TOOL>_VERSION=...` pin from the Dockerfile and compares it with
/// the newest matching GitHub release. A single-file Java program — run it directly
/// with `java scripts/CheckToolVersions.java` (no build step); it needs only a JDK,
/// which the dev image and CI already provide. The apt-installed tools (qpdf,
/// poppler-utils, webp) track the Ubuntu base and are not pinned here, so they are
/// intentionally not checked.
///
/// Exit status is 1 if any tool is behind, 2 if a lookup fails, 0 if all current.
/// Set GITHUB_TOKEN to raise the GitHub API rate limit (optional). Run from the repo
/// root (where the Dockerfile lives).
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CheckToolVersions {

    /** A pinned tool: its Dockerfile ARG, GitHub repo, and a tag pattern whose group 1 is the version. */
    record Tool(String arg, String repo, Pattern tag) {}

    // Most tags are vX.Y.Z (an optional leading v); biome tags are @biomejs/biome@X.Y.Z.
    private static final Pattern V_TAG = Pattern.compile("^v?(\\d.+)$");

    private static final List<Tool> TOOLS =
            List.of(
                    new Tool("JUST_VERSION", "casey/just", V_TAG),
                    new Tool("LEFTHOOK_VERSION", "evilmartians/lefthook", V_TAG),
                    new Tool("TYPOS_VERSION", "crate-ci/typos", V_TAG),
                    new Tool("TAPLO_VERSION", "tamasfe/taplo", V_TAG),
                    new Tool("BIOME_VERSION", "biomejs/biome", Pattern.compile("^@biomejs/biome@(\\d.+)$")),
                    new Tool("YAMLFMT_VERSION", "google/yamlfmt", V_TAG),
                    new Tool("ACTIONLINT_VERSION", "rhysd/actionlint", V_TAG),
                    new Tool("JBIG2ENC_VERSION", "agl/jbig2enc", V_TAG));

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public static void main(String[] args) throws Exception {
        Map<String, String> pins = pinnedVersions(Path.of("Dockerfile"));
        int outdated = 0;
        int errors = 0;

        System.out.printf("%-20s %-12s %-12s %s%n", "tool", "pinned", "latest", "status");
        System.out.println("-".repeat(58));

        for (Tool tool : TOOLS) {
            String pinned = pins.getOrDefault(tool.arg(), "?");
            String latest;
            try {
                latest = latestVersion(tool);
            } catch (IOException | InterruptedException e) {
                System.out.printf(
                        "%-20s %-12s %-12s %s%n",
                        tool.arg(), pinned, "?", "ERROR (" + tool.repo() + "): " + e.getMessage());
                errors++;
                continue;
            }
            if (latest == null) {
                System.out.printf(
                        "%-20s %-12s %-12s %s%n",
                        tool.arg(), pinned, "?", "no matching release (" + tool.repo() + ")");
                errors++;
            } else if (compareKey(versionKey(latest), versionKey(pinned)) > 0) {
                System.out.printf(
                        "%-20s %-12s %-12s %s%n",
                        tool.arg(), pinned, latest, "OUTDATED (" + tool.repo() + ")");
                outdated++;
            } else {
                System.out.printf("%-20s %-12s %-12s %s%n", tool.arg(), pinned, latest, "ok");
            }
        }

        System.out.println();
        if (outdated > 0) {
            System.out.println(outdated + " tool(s) behind latest — bump the ARG in the Dockerfile.");
        }
        if (errors > 0) {
            System.out.println(errors + " tool(s) could not be checked.");
        }
        if (outdated == 0 && errors == 0) {
            System.out.println("all pinned tools are at the latest release.");
        }
        System.exit(outdated > 0 ? 1 : (errors > 0 ? 2 : 0));
    }

    /** The {@code ARG <name>=<value>} pins for the known tools, read from the Dockerfile. */
    private static Map<String, String> pinnedVersions(Path dockerfile) throws IOException {
        String text = Files.readString(dockerfile);
        Map<String, String> pins = new LinkedHashMap<>();
        for (Tool tool : TOOLS) {
            Matcher m =
                    Pattern.compile("^ARG " + tool.arg() + "=(.+)$", Pattern.MULTILINE).matcher(text);
            if (m.find()) {
                pins.put(tool.arg(), m.group(1).strip());
            }
        }
        return pins;
    }

    /** The newest non-draft, non-prerelease release version matching the tool's tag pattern, or null. */
    private static String latestVersion(Tool tool) throws IOException, InterruptedException {
        Object parsed =
                Json.parse(
                        apiGet(
                                "https://api.github.com/repos/"
                                        + tool.repo()
                                        + "/releases?per_page=100"));
        if (!(parsed instanceof List<?> releases)) {
            return null;
        }
        String best = null;
        for (Object element : releases) {
            if (!(element instanceof Map<?, ?> release)) {
                continue;
            }
            if (Boolean.TRUE.equals(release.get("draft"))
                    || Boolean.TRUE.equals(release.get("prerelease"))) {
                continue;
            }
            if (!(release.get("tag_name") instanceof String tagName)) {
                continue;
            }
            Matcher m = tool.tag().matcher(tagName);
            if (m.matches()
                    && (best == null || compareKey(versionKey(m.group(1)), versionKey(best)) > 0)) {
                best = m.group(1);
            }
        }
        return best;
    }

    private static String apiGet(String url) throws IOException, InterruptedException {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "despeckle-tool-version-check")
                        .timeout(Duration.ofSeconds(30));
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response =
                HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** The integer runs in a version, compared lexicographically (1.10 > 1.9). */
    private static int[] versionKey(String version) {
        Matcher m = DIGITS.matcher(version);
        List<Integer> parts = new ArrayList<>();
        while (m.find()) {
            parts.add(Integer.parseInt(m.group()));
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int compareKey(int[] a, int[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private CheckToolVersions() {}

    /**
     * A tiny recursive-descent JSON reader — just enough to walk GitHub's releases array (objects,
     * arrays, strings, numbers, booleans, null). The JDK ships no JSON parser, and a single-file
     * program has only the JDK on its classpath, so this stays self-contained.
     */
    static final class Json {
        private final String src;
        private int pos;

        private Json(String src) {
            this.src = src;
        }

        static Object parse(String src) {
            Json json = new Json(src);
            json.skipWhitespace();
            return json.readValue();
        }

        private Object readValue() {
            skipWhitespace();
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = src.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = src.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = src.charAt(pos++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + escape);
                }
            }
        }

        private Boolean readBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw error("bad literal");
        }

        private Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw error("bad literal");
        }

        private Double readNumber() {
            int start = pos;
            while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.valueOf(src.substring(start, pos));
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private void expect(char c) {
            if (src.charAt(pos++) != c) {
                throw error("expected '" + c + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("JSON: " + message + " at offset " + pos);
        }
    }
}
