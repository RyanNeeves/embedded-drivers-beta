package com.cdata.changelog;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

/**
 * CData Changelog Review — MCP Server for Claude Desktop.
 * See README.md for installation and configuration instructions.
 */
public class ChangelogReviewServer {

    // ============================================================
    //  CONFIGURATION
    // ============================================================

    private static final String BASE_URL       = "https://downloads.cdata.com/cdataoembuilds";
    private static final String CHANGELOG_ROOT = "https://downloads.cdata.com/cdataoembuilds/changelogs";

    private static final List<String>              EDITIONS;
    private static final Map<String, List<String>> EDITION_SUBPATHS;
    private static final Map<String, String>       EDITION_CHANGELOG_CATEGORY;
    private static final Map<String, Integer>      HARDCODED_RELEASES;

    static {
        EDITIONS = Collections.unmodifiableList(Arrays.asList(
            "JDBC", "ADO .NET FRAMEWORK", "ADO .NET STANDARD",
            "ODBC UNIX", "ODBC WINDOWS",
            "PYTHON MAC", "PYTHON UNIX", "PYTHON WINDOWS"
        ));

        Map<String, List<String>> sp = new LinkedHashMap<String, List<String>>();
        sp.put("JDBC",               Arrays.asList("jdbc"));
        sp.put("ADO .NET FRAMEWORK", Arrays.asList("ado/net40"));
        sp.put("ADO .NET STANDARD",  Arrays.asList("ado/netstandard20"));
        sp.put("ODBC UNIX",          Arrays.asList("odbc/linux/x64"));
        sp.put("ODBC WINDOWS",       Arrays.asList("odbc/net40/x64"));
        sp.put("PYTHON MAC",         Arrays.asList("python/mac"));
        sp.put("PYTHON UNIX",        Arrays.asList("python/unix"));
        sp.put("PYTHON WINDOWS",     Arrays.asList("python/win"));
        EDITION_SUBPATHS = Collections.unmodifiableMap(sp);

        Map<String, String> cc = new LinkedHashMap<String, String>();
        cc.put("JDBC",               "jdbc");
        cc.put("ADO .NET FRAMEWORK", "ado");
        cc.put("ADO .NET STANDARD",  "ado");
        cc.put("ODBC UNIX",          "odbc");
        cc.put("ODBC WINDOWS",       "odbc");
        cc.put("PYTHON MAC",         "python");
        cc.put("PYTHON UNIX",        "python");
        cc.put("PYTHON WINDOWS",     "python");
        EDITION_CHANGELOG_CATEGORY = Collections.unmodifiableMap(cc);

        Map<String, Integer> hc = new LinkedHashMap<String, Integer>();
        hc.put("v25u1", 9434);
        HARDCODED_RELEASES = Collections.unmodifiableMap(hc);
    }

    // ============================================================
    //  HTTP HELPERS
    // ============================================================

    private static final class HttpResult {
        final int    status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }

    private static HttpResult httpGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        int status = conn.getResponseCode();
        InputStream is = (status < 400) ? conn.getInputStream() : conn.getErrorStream();
        String body = "";
        if (is != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                body = baos.toString("UTF-8");
            } finally {
                is.close();
            }
        }
        conn.disconnect();
        return new HttpResult(status, body);
    }

    // ============================================================
    //  UTILITIES
    // ============================================================

    private static String normalizeEdition(String edition) {
        String eu = edition.toUpperCase(Locale.ROOT).trim();
        if (EDITION_SUBPATHS.containsKey(eu)) return eu;
        throw new IllegalArgumentException(
            "Unknown edition '" + edition + "'. Valid editions: " + String.join(", ", EDITIONS));
    }

    private static String releaseTag(int year, int releaseNumber) {
        return String.format("v%du%d", year % 100, releaseNumber);
    }

    private static String majorVersionTag(int year) {
        return String.format("v%d", year % 100);
    }

    private static List<String> editionSubpaths(String edition, String tag) {
        List<String> result = new ArrayList<String>();
        for (String p : EDITION_SUBPATHS.get(edition)) result.add(tag + "/" + p);
        return result;
    }

    /**
     * Parses a bld-* marker filename, returning {"obj_name", "build_number"} or null.
     */
    private static Map<String, Object> parseBuildMarker(String filename, String edition) {
        if (!filename.startsWith("bld-")) return null;
        String body = filename.substring(4);
        String eu = edition.toUpperCase(Locale.ROOT);
        Pattern pat;
        if      (eu.startsWith("ADO"))    pat = Pattern.compile("^System\\.Data\\.CData\\.(.+)\\.(\\d+)$");
        else if (eu.equals("JDBC"))       pat = Pattern.compile("^cdata\\.jdbc\\.(.+)\\.(\\d+)$");
        else if (eu.startsWith("ODBC"))   pat = Pattern.compile("^[Cc][Dd]ata\\.[Oo][Dd][Bb][Cc]\\.(.+)\\.(\\d+)$");
        else if (eu.startsWith("PYTHON")) pat = Pattern.compile("^(.+)\\.(\\d+)$");
        else return null;
        Matcher m = pat.matcher(body);
        if (m.matches()) {
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("obj_name",     m.group(1));
            result.put("build_number", Integer.parseInt(m.group(2)));
            return result;
        }
        return null;
    }

    /** Extracts VERMIN (build number) from "VERMAJ.0.VERMIN". Returns -1 on failure. */
    private static int buildFromVersion(String versionStr) {
        String[] parts = versionStr.trim().split("\\.");
        if (parts.length >= 3) {
            try { return Integer.parseInt(parts[parts.length - 1]); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static int intArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) return Integer.parseInt(((String) val).trim());
        throw new IllegalArgumentException("Missing or invalid argument: " + key);
    }

    private static Integer optIntArg(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            String sv = ((String) val).trim();
            return sv.isEmpty() ? null : Integer.parseInt(sv);
        }
        return null;
    }

    private static CallToolResult ok(String text) {
        return CallToolResult.builder()
            .content(Collections.singletonList((McpSchema.Content) new TextContent(text)))
            .isError(false)
            .build();
    }

    private static CallToolResult err(String message) {
        return CallToolResult.builder()
            .content(Collections.singletonList((McpSchema.Content) new TextContent(message)))
            .isError(true)
            .build();
    }

    private static String stripTrailing(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return i < 0 ? "" : s.substring(0, i + 1);
    }

    private static String xmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&apos;", "'").replace("&quot;", "\"");
    }

    private static String fileNameFromKey(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    // ============================================================
    //  S3 OPERATIONS
    // ============================================================

    private static List<String> listS3Objects(String prefix) throws IOException {
        List<String> keys = new ArrayList<String>();
        String continuationToken = null;
        do {
            StringBuilder url = new StringBuilder(BASE_URL)
                .append("/?list-type=2&prefix=")
                .append(URLEncoder.encode(prefix, "UTF-8"));
            if (continuationToken != null)
                url.append("&continuation-token=").append(URLEncoder.encode(continuationToken, "UTF-8"));
            HttpResult res = httpGet(url.toString());
            if (res.status != 200)
                throw new IOException("S3 list returned HTTP " + res.status + " for prefix: " + prefix);
            Matcher km = Pattern.compile("<Key>([^<]+)</Key>").matcher(res.body);
            while (km.find()) keys.add(xmlUnescape(km.group(1)));
            if (res.body.contains("<IsTruncated>true</IsTruncated>")) {
                Matcher tm = Pattern.compile("<NextContinuationToken>([^<]+)</NextContinuationToken>").matcher(res.body);
                continuationToken = tm.find() ? tm.group(1) : null;
            } else {
                continuationToken = null;
            }
        } while (continuationToken != null);
        return keys;
    }

    private static Map<String, Object> makeRelease(int yy, int rel) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        String tag = String.format("v%du%d", yy, rel);
        r.put("tag",            tag);
        r.put("year",           2000 + yy);
        r.put("release_number", rel);
        r.put("label",          String.format("%d U%d", 2000 + yy, rel));
        if (HARDCODED_RELEASES.containsKey(tag))
            r.put("build_number", HARDCODED_RELEASES.get(tag));
        return r;
    }

    private static List<Map<String, Object>> listAvailableReleases() throws IOException {
        HttpResult res = httpGet(BASE_URL + "/?list-type=2&prefix=v&delimiter=/");
        if (res.status != 200) throw new IOException("S3 release discovery returned HTTP " + res.status);

        Set<String> seen = new HashSet<String>();
        List<Map<String, Object>> releases = new ArrayList<Map<String, Object>>();

        Matcher m = Pattern.compile("<Prefix>v(\\d{2})u(\\d+)/</Prefix>").matcher(res.body);
        while (m.find()) {
            int yy = Integer.parseInt(m.group(1)), rel = Integer.parseInt(m.group(2));
            Map<String, Object> r = makeRelease(yy, rel);
            seen.add((String) r.get("tag"));
            releases.add(r);
        }

        for (Map.Entry<String, Integer> e : HARDCODED_RELEASES.entrySet()) {
            if (!seen.contains(e.getKey())) {
                Matcher hm = Pattern.compile("v(\\d{2})u(\\d+)").matcher(e.getKey());
                if (hm.matches())
                    releases.add(makeRelease(Integer.parseInt(hm.group(1)), Integer.parseInt(hm.group(2))));
            }
        }

        Collections.sort(releases, new Comparator<Map<String, Object>>() {
            @Override public int compare(Map<String, Object> a, Map<String, Object> b) {
                int ya = (Integer) a.get("year"), yb = (Integer) b.get("year");
                if (ya != yb) return Integer.compare(yb, ya);
                return Integer.compare((Integer) b.get("release_number"), (Integer) a.get("release_number"));
            }
        });
        return releases;
    }

    /**
     * Validates that a release tag exists in the bucket.
     * Returns null if valid, or a CallToolResult error listing available releases.
     */
    private static CallToolResult validateRelease(int year, int releaseNumber) {
        try {
            String tag = releaseTag(year, releaseNumber);
            List<Map<String, Object>> releases = listAvailableReleases();
            for (Map<String, Object> r : releases) {
                if (tag.equals(r.get("tag"))) return null; // found
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Release '" + tag + "' does not exist in the bucket.\n\nAvailable releases:\n");
            for (Map<String, Object> r : releases)
                sb.append(String.format("  %s  (year=%d, release_number=%d)%n", r.get("label"), r.get("year"), r.get("release_number")));
            sb.append("\nPlease ask the user which release they want.");
            return err(stripTrailing(sb.toString()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return err("Error checking releases: " + e.getMessage());
        }
    }

    // ============================================================
    //  CSV PARSING
    // ============================================================

    private static List<Map<String, String>> parseCsv(String csv) {
        List<Map<String, String>> rows = new ArrayList<Map<String, String>>();
        String[] lines = csv.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        if (lines.length < 2) return rows;
        String[] headers = parseCsvLine(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] fields = parseCsvLine(line);
            Map<String, String> row = new LinkedHashMap<String, String>();
            for (int j = 0; j < headers.length; j++)
                row.put(headers[j].trim(), j < fields.length ? fields[j].trim() : "");
            rows.add(row);
        }
        return rows;
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char[] chars = line.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '"') {
                if (inQuotes && i + 1 < chars.length && chars[i + 1] == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    // ============================================================
    //  TOOL SCHEMAS
    // ============================================================

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static McpSchema.JsonSchema noArgsSchema() {
        return new McpSchema.JsonSchema("object",
            Collections.<String, Object>emptyMap(), Collections.<String>emptyList(), null, null, null);
    }

    private static McpSchema.JsonSchema getChangelogSchema() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("edition",              prop("string",  "One of: JDBC, ADO .NET FRAMEWORK, ADO .NET STANDARD, ODBC UNIX, ODBC WINDOWS, PYTHON MAC, PYTHON UNIX, PYTHON WINDOWS"));
        props.put("obj_name",             prop("string",  "Connector OBJNAME (e.g. Salesforce)"));
        props.put("major_version",        prop("integer", "Major version year from list_releases (e.g. 2025). Each major version has its own independent changelog."));
        props.put("after_release_number", prop("integer", "The U-number exactly as shown by list_releases. For '2025 U1' use 1, for '2025 U2' use 2. Must be >= 1. Do NOT subtract or compute — use the number directly."));
        props.put("after_date",           prop("string",  "Return entries after this date (ISO 8601 format, e.g. '2025-10-28'). Use for date-based queries like 'changes in the last month'."));
        props.put("after_build",          prop("integer", "Return entries after this build number. Only use if the user provides a specific build number. Prefer after_date or after_release_number instead."));
        return new McpSchema.JsonSchema("object", props,
            Arrays.asList("edition", "obj_name", "major_version"), null, null, null);
    }

    // ============================================================
    //  TOOL HANDLERS
    // ============================================================

    private static CallToolResult handleListReleases(Map<String, Object> args) {
        try {
            List<Map<String, Object>> releases = listAvailableReleases();
            if (releases.isEmpty()) return ok("No releases found.");
            StringBuilder sb = new StringBuilder("Available releases (newest first):\n");
            for (Map<String, Object> r : releases) {
                sb.append(String.format("  %s  (major_version: %d, release_number: %d)%n",
                    r.get("label"), r.get("year"), r.get("release_number")));
            }
            return ok(stripTrailing(sb.toString()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return err("Error listing releases: " + e.getMessage());
        }
    }

    private static int dateToBuild(String iso) {
        String[] parts = iso.split("-");
        if (parts.length != 3) throw new IllegalArgumentException("Invalid date format, expected YYYY-MM-DD: " + iso);
        int y = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        int d = Integer.parseInt(parts[2]);
        long epoch2000 = 946684800000L; // 2000-01-01T00:00:00 UTC in millis
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(y, m - 1, d, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return (int) ((cal.getTimeInMillis() - epoch2000) / 86400000L);
    }

    private static CallToolResult handleGetChangelog(Map<String, Object> args) {
        Integer majorVersion       = optIntArg(args, "major_version");
        Integer afterBuild         = optIntArg(args, "after_build");
        Integer afterReleaseNumber = optIntArg(args, "after_release_number");
        String  afterDate          = args.get("after_date") != null ? args.get("after_date").toString() : null;

        if (majorVersion == null)
            return err("major_version is required. Call list_releases to see available major versions.");

        // Count how many "after" params were provided
        int afterCount = (afterBuild != null ? 1 : 0) + (afterReleaseNumber != null ? 1 : 0) + (afterDate != null ? 1 : 0);
        if (afterCount == 0)
            return err("Provide exactly one of: after_release_number, after_date, or after_build.");
        if (afterCount > 1)
            return err("Provide only one of: after_release_number, after_date, or after_build.");

        if (afterReleaseNumber != null && afterReleaseNumber < 1)
            return err("after_release_number must be >= 1. Use the U-number directly from list_releases (e.g. 1 for U1, 2 for U2).");
        if (afterBuild != null && afterBuild < 1)
            return err("after_build must be a positive build number.");

        // Convert after_date to a build number
        if (afterDate != null) {
            try {
                afterBuild = dateToBuild(afterDate);
            } catch (Exception e) {
                return err("Invalid after_date: " + e.getMessage());
            }
        }

        String edition;
        try { edition = normalizeEdition((String) args.get("edition")); }
        catch (IllegalArgumentException e) { return err(e.getMessage()); }

        String objName = (String) args.get("obj_name");
        String category = EDITION_CHANGELOG_CATEGORY.get(edition);

        try {
            int baselineBuild;
            if (afterReleaseNumber != null) {
                String tag = releaseTag(majorVersion, afterReleaseNumber);

                if (HARDCODED_RELEASES.containsKey(tag)) {
                    baselineBuild = HARDCODED_RELEASES.get(tag);
                } else {
                    List<String> paths = editionSubpaths(edition, tag);
                    Integer found = null;
                    outer:
                    for (String p : paths) {
                        for (String key : listS3Objects(p + "/")) {
                            Map<String, Object> parsed = parseBuildMarker(fileNameFromKey(key), edition);
                            if (parsed != null && ((String) parsed.get("obj_name")).equalsIgnoreCase(objName)) {
                                found = (Integer) parsed.get("build_number");
                                break outer;
                            }
                        }
                    }
                    if (found == null) {
                        CallToolResult releaseCheck = validateRelease(majorVersion, afterReleaseNumber);
                        if (releaseCheck != null) return releaseCheck;
                        return err("No build marker found for '" + objName + "' in " +
                                   edition + " / " + tag + ". Verify the OBJNAME spelling.");
                    }
                    baselineBuild = found;
                }
            } else {
                baselineBuild = afterBuild;
            }

            // Query changelog for the specified major version
            String mvTag = majorVersionTag(majorVersion);
            String objLower = objName.toLowerCase(Locale.ROOT);
            String url = CHANGELOG_ROOT + "/" + mvTag + "/" + category + "/" + objLower + "/changelog.csv";
            HttpResult res = httpGet(url);

            if (res.status == 404)
                return err("No changelog found for '" + objName + "' (" + edition + ") in major version " + majorVersion + ".");
            if (res.status != 200)
                return err("HTTP " + res.status + " fetching changelog for '" + objName + "'.");

            List<Map<String, String>> rows = parseCsv(res.body);
            if (rows.isEmpty() || !rows.get(0).containsKey("Version"))
                return ok("Changelog is empty for '" + objName + "' in major version " + majorVersion + ".");

            List<Map<String, String>> filtered = new ArrayList<Map<String, String>>();
            for (Map<String, String> r : rows) {
                if (buildFromVersion(r.containsKey("Version") ? r.get("Version") : "") > baselineBuild)
                    filtered.add(r);
            }

            if (filtered.isEmpty())
                return ok("No changelog entries after build " + baselineBuild + " for '" + objName + "' in major version " + majorVersion + ".");

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Changelog: %s (%s) v%d — %d entr%s after build %d%n%n",
                objName, edition, majorVersion, filtered.size(), filtered.size() == 1 ? "y" : "ies", baselineBuild));
            for (Map<String, String> r : filtered) {
                sb.append(String.format("  [%s] v%s  %s / %s%n    %s%n",
                    r.containsKey("Date")            ? r.get("Date")            : "",
                    r.containsKey("Version")         ? r.get("Version")         : "",
                    r.containsKey("Change Category") ? r.get("Change Category") : "",
                    r.containsKey("Change Type")     ? r.get("Change Type")     : "",
                    r.containsKey("Description")     ? r.get("Description")     : ""));
            }
            return ok(stripTrailing(sb.toString()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return err("Error: " + e.getMessage());
        }
    }

    // ============================================================
    //  MAIN
    // ============================================================

    public static void main(String[] args) throws Exception {
        StdioServerTransportProvider transport =
            new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transport)
            .serverInfo("cdata-changelog-review-mcp", "1.0.0")
            .capabilities(ServerCapabilities.builder().tools(true).build())

            .toolCall(
                McpSchema.Tool.builder()
                    .name("list_releases")
                    .description(
                        "List available CData connector releases, newest first. " +
                        "MUST be called before get_changelog — only use release numbers returned here. " +
                        "Do NOT guess or assume release numbers — only releases returned by this tool are valid. " +
                        "No arguments required.")
                    .inputSchema(noArgsSchema())
                    .build(),
                (exchange, request) -> handleListReleases(
                    request.arguments() != null ? request.arguments() : Collections.<String, Object>emptyMap()))

            .toolCall(
                McpSchema.Tool.builder()
                    .name("get_changelog")
                    .description(
                        "Get changelog entries for a CData connector since a release, date, or build. " +
                        "Each major version has its own independent changelog. " +
                        "IMPORTANT: Call list_releases first. Do NOT invent or guess release numbers. " +
                        "Requires: obj_name (e.g. MongoDB, Salesforce), edition, and major_version (from list_releases, e.g. 2025). " +
                        "The major_version is NOT the current calendar year — it is the version year from list_releases. " +
                        "Plus EXACTLY ONE of: " +
                        "after_release_number (U-number, e.g. 2 for U2), " +
                        "after_date (ISO 8601 date, e.g. '2025-10-28' — use for 'changes in the last month'), or " +
                        "after_build (integer build number). " +
                        "If the user doesn't specify a release, date, or build, ASK. " +
                        "If edition not specified, ASK. " +
                        "Editions: JDBC, ADO .NET FRAMEWORK, ADO .NET STANDARD, ODBC UNIX, ODBC WINDOWS, PYTHON MAC, PYTHON UNIX, PYTHON WINDOWS.")
                    .inputSchema(getChangelogSchema())
                    .build(),
                (exchange, request) -> handleGetChangelog(
                    request.arguments() != null ? request.arguments() : Collections.<String, Object>emptyMap()))

            .build();

        System.err.println("CData Changelog Review MCP server started.");
        Thread.currentThread().join();
    }
}
