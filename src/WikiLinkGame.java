import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public class WikiLinkGame {
    private static final String DEFAULT_START = "Computer science";
    private static final String DEFAULT_TARGET = "Philosophy";

    public static void main(String[] args) throws Exception {
        Path baseDir = Paths.get(System.getProperty("user.dir"));
        CacheStore cache = new CacheStore(baseDir.resolve("data"));
        WikipediaClient client = new WikipediaClient("en.wikipedia.org");
        LinkService service = new LinkService(cache, client);

        String command = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if ("demo".equals(command)) {
            String start = args.length >= 2 ? joinArgs(args, 1, args.length) : DEFAULT_START;
            runDemo(service, start);
        } else if ("play".equals(command)) {
            String start = args.length >= 2 ? args[1] : DEFAULT_START;
            String target = args.length >= 3 ? args[2] : DEFAULT_TARGET;
            runGame(service, start, target);
        } else if ("refresh-cache".equals(command)) {
            runRefresh(cache, client, service);
        } else if ("cache-stats".equals(command)) {
            printCacheStats(cache);
        } else {
            printHelp();
        }
    }

    private static void runDemo(LinkService service, String start) throws Exception {
        System.out.println("Demo page: " + start);

        CachedLinks first = service.getLinks(start);
        System.out.println("First lookup: " + first.source + ", " + first.links.size() + " article links");

        CachedLinks second = service.getLinks(start);
        System.out.println("Second lookup: " + second.source + ", " + second.links.size() + " article links");

        System.out.println();
        System.out.println("Sample links:");
        int limit = Math.min(15, second.links.size());
        for (int i = 0; i < limit; i++) {
            System.out.println("  " + (i + 1) + ". " + second.links.get(i));
        }
        System.out.println();
        System.out.println("Cache directory: " + service.cacheDirectory());
    }

    private static void runGame(LinkService service, String start, String target) throws Exception {
        Scanner scanner = new Scanner(System.in, "UTF-8");
        try {
            String current = start;
            List<String> path = new ArrayList<String>();
            path.add(current);

            System.out.println("Target: " + target);
            System.out.println("Type a number, an exact article title, 'filter text', 'path', or 'quit'.");

            while (true) {
                if (normalize(current).equals(normalize(target))) {
                    System.out.println("Reached target in " + (path.size() - 1) + " moves.");
                    printPath(path);
                    return;
                }

                CachedLinks cachedLinks = service.getLinks(current);
                List<String> links = cachedLinks.links;
                System.out.println();
                System.out.println("Current: " + current + " (" + cachedLinks.source + ", " + links.size() + " links)");
                printLinkPage(links, "");
                System.out.print("> ");

                if (!scanner.hasNextLine()) {
                    return;
                }
                String input = scanner.nextLine().trim();
                if (input.length() == 0) {
                    continue;
                }
                if ("quit".equalsIgnoreCase(input)) {
                    printPath(path);
                    return;
                }
                if ("path".equalsIgnoreCase(input)) {
                    printPath(path);
                    continue;
                }
                if (input.toLowerCase(Locale.ROOT).startsWith("filter ")) {
                    String filter = input.substring("filter ".length()).trim();
                    printLinkPage(links, filter);
                    continue;
                }

                String next = chooseLink(links, input);
                if (next == null) {
                    System.out.println("That is not one of the outgoing article links. Try a number or exact title.");
                    continue;
                }
                current = next;
                path.add(current);
            }
        } finally {
            scanner.close();
        }
    }

    private static void runRefresh(CacheStore cache, WikipediaClient client, LinkService service) throws Exception {
        List<PageRecord> cachedPages = cache.pagesWithCachedLinks();
        System.out.println("Cached nodes to check: " + cachedPages.size());
        if (cachedPages.isEmpty()) {
            return;
        }

        int unchanged = 0;
        int refreshed = 0;
        int missing = 0;
        int batchSize = 50;

        for (int offset = 0; offset < cachedPages.size(); offset += batchSize) {
            List<PageRecord> batch = cachedPages.subList(offset, Math.min(offset + batchSize, cachedPages.size()));
            Map<Integer, PageInfo> infos = client.fetchInfoByPageIds(batch);
            for (PageRecord page : batch) {
                PageInfo info = infos.get(Integer.valueOf(page.pageId));
                if (info == null || info.missing) {
                    missing++;
                    cache.markChecked(page.pageId, page.lastRevisionId);
                    continue;
                }

                if (info.lastRevisionId == page.lastRevisionId) {
                    unchanged++;
                    cache.markChecked(page.pageId, info.lastRevisionId);
                    continue;
                }

                service.refreshLinks(info.title);
                refreshed++;
                System.out.println("Refreshed: " + info.title + " (" + page.lastRevisionId + " -> " + info.lastRevisionId + ")");
            }
            cache.save();
        }

        System.out.println("Refresh complete. unchanged=" + unchanged + ", refreshed=" + refreshed + ", missing=" + missing);
    }

    private static void printCacheStats(CacheStore cache) {
        int edgeCount = 0;
        for (List<LinkRecord> links : cache.linksByPageId.values()) {
            edgeCount += links.size();
        }
        System.out.println("Cached pages: " + cache.pagesById.size());
        System.out.println("Cached graph edges: " + edgeCount);
        System.out.println("Cache directory: " + cache.directory);
    }

    private static void printHelp() {
        System.out.println("Wiki Link Game");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  demo [start title]             Fetch links twice to show network then cache");
        System.out.println("  play [start title] [target]    Play the Wikipedia link game");
        System.out.println("  refresh-cache                  Check cached nodes and refresh changed ones");
        System.out.println("  cache-stats                    Print cached node and edge counts");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -cp build WikiLinkGame demo \"Computer science\"");
        System.out.println("  java -cp build WikiLinkGame play \"Computer science\" \"Philosophy\"");
    }

    private static void printLinkPage(List<String> links, String filter) {
        String normalizedFilter = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        int shown = 0;
        for (int i = 0; i < links.size(); i++) {
            String title = links.get(i);
            if (normalizedFilter.length() > 0 && !title.toLowerCase(Locale.ROOT).contains(normalizedFilter)) {
                continue;
            }
            System.out.println("  " + (i + 1) + ". " + title);
            shown++;
            if (shown >= 30) {
                break;
            }
        }
        if (shown == 0) {
            System.out.println("  No matching links.");
        } else if (shown < links.size()) {
            System.out.println("  ...showing up to 30. Use 'filter text' or type an exact title.");
        }
    }

    private static String chooseLink(List<String> links, String input) {
        try {
            int selected = Integer.parseInt(input);
            if (selected >= 1 && selected <= links.size()) {
                return links.get(selected - 1);
            }
        } catch (NumberFormatException ignored) {
            // Fall through to exact title match.
        }

        String normalizedInput = normalize(input);
        for (String link : links) {
            if (normalize(link).equals(normalizedInput)) {
                return link;
            }
        }
        return null;
    }

    private static void printPath(List<String> path) {
        System.out.println("Path:");
        for (int i = 0; i < path.size(); i++) {
            System.out.println("  " + i + ". " + path.get(i));
        }
    }

    private static String joinArgs(String[] args, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(args[i]);
        }
        return result.toString();
    }

    private static String normalize(String title) {
        return title == null ? "" : title.trim().replace('_', ' ').replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static final class LinkService {
        private final CacheStore cache;
        private final WikipediaClient client;

        LinkService(CacheStore cache, WikipediaClient client) {
            this.cache = cache;
            this.client = client;
        }

        CachedLinks getLinks(String title) throws Exception {
            PageRecord page = cache.findPage(title);
            if (page != null && cache.hasLinks(page.pageId)) {
                return new CachedLinks(page.title, cache.getLinks(page.pageId), "cache");
            }
            return refreshLinks(title);
        }

        CachedLinks refreshLinks(String title) throws Exception {
            PageLinks fetched = client.fetchLinks(title);
            cache.storePageLinks(fetched);
            cache.save();
            return new CachedLinks(fetched.title, fetched.links, "network");
        }

        Path cacheDirectory() {
            return cache.directory;
        }
    }

    static final class CachedLinks {
        final String title;
        final List<String> links;
        final String source;

        CachedLinks(String title, List<String> links, String source) {
            this.title = title;
            this.links = links;
            this.source = source;
        }
    }

    static final class WikipediaClient {
        private static final String USER_AGENT = "WikiLinkGamePrototype/0.1 (local educational project)";
        private final String host;
        private long lastRequestAtMillis = 0L;

        WikipediaClient(String host) {
            this.host = host;
        }

        PageLinks fetchLinks(String title) throws Exception {
            LinkedHashSet<String> allLinks = new LinkedHashSet<String>();
            PageInfo pageInfo = null;
            Map<String, String> continueParams = new LinkedHashMap<String, String>();

            while (true) {
                Map<String, String> params = new LinkedHashMap<String, String>();
                params.put("action", "query");
                params.put("format", "json");
                params.put("formatversion", "2");
                params.put("redirects", "1");
                params.put("titles", title);
                params.put("prop", "info|links");
                params.put("plnamespace", "0");
                params.put("pllimit", "max");
                params.putAll(continueParams);

                Map<String, Object> root = request(params);
                Map<String, Object> query = asObject(root.get("query"));
                if (query == null) {
                    throw new IOException("No query object in Wikipedia response.");
                }

                List<Object> pages = asArray(query.get("pages"));
                if (pages == null || pages.isEmpty()) {
                    throw new IOException("No page found for title: " + title);
                }

                Map<String, Object> page = asObject(pages.get(0));
                if (page == null || page.containsKey("missing")) {
                    throw new IOException("Wikipedia page does not exist: " + title);
                }

                if (pageInfo == null) {
                    pageInfo = PageInfo.fromJson(page);
                }

                List<Object> links = asArray(page.get("links"));
                if (links != null) {
                    for (Object item : links) {
                        Map<String, Object> link = asObject(item);
                        if (link == null) {
                            continue;
                        }
                        int namespace = intValue(link.get("ns"), -1);
                        String linkTitle = stringValue(link.get("title"));
                        if (namespace == 0 && linkTitle != null && linkTitle.length() > 0) {
                            allLinks.add(linkTitle);
                        }
                    }
                }

                Map<String, Object> next = asObject(root.get("continue"));
                if (next == null || !next.containsKey("plcontinue")) {
                    break;
                }
                continueParams.clear();
                for (Map.Entry<String, Object> entry : next.entrySet()) {
                    continueParams.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }

            if (pageInfo == null) {
                throw new IOException("No page info found for title: " + title);
            }
            List<String> sortedLinks = new ArrayList<String>(allLinks);
            Collections.sort(sortedLinks, String.CASE_INSENSITIVE_ORDER);
            return new PageLinks(pageInfo.pageId, pageInfo.title, pageInfo.lastRevisionId, sortedLinks);
        }

        Map<Integer, PageInfo> fetchInfoByPageIds(List<PageRecord> pages) throws Exception {
            Map<Integer, PageInfo> results = new HashMap<Integer, PageInfo>();
            if (pages.isEmpty()) {
                return results;
            }

            StringBuilder pageIds = new StringBuilder();
            for (PageRecord page : pages) {
                if (pageIds.length() > 0) {
                    pageIds.append('|');
                }
                pageIds.append(page.pageId);
            }

            Map<String, String> params = new LinkedHashMap<String, String>();
            params.put("action", "query");
            params.put("format", "json");
            params.put("formatversion", "2");
            params.put("pageids", pageIds.toString());
            params.put("prop", "info");

            Map<String, Object> root = request(params);
            Map<String, Object> query = asObject(root.get("query"));
            List<Object> responsePages = query == null ? null : asArray(query.get("pages"));
            if (responsePages == null) {
                return results;
            }

            for (Object item : responsePages) {
                Map<String, Object> page = asObject(item);
                if (page == null) {
                    continue;
                }
                PageInfo info = PageInfo.fromJson(page);
                results.put(Integer.valueOf(info.pageId), info);
            }
            return results;
        }

        private Map<String, Object> request(Map<String, String> params) throws Exception {
            throttle();
            String url = "https://" + host + "/w/api.php?" + encodeParams(params);
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");

            int status = connection.getResponseCode();
            if (status == 429) {
                String retryAfter = connection.getHeaderField("Retry-After");
                long sleepSeconds = retryAfter == null ? 30L : Long.parseLong(retryAfter.trim());
                TimeUnit.SECONDS.sleep(Math.min(sleepSeconds, 300L));
                connection.disconnect();
                return request(params);
            }

            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getHeaderField("Content-Encoding"))) {
                stream = new GZIPInputStream(stream);
            }
            String body = readAll(stream);
            connection.disconnect();

            if (status >= 400) {
                throw new IOException("Wikipedia API request failed with HTTP " + status + ": " + body);
            }

            Object parsed = Json.parse(body);
            Map<String, Object> root = asObject(parsed);
            if (root == null) {
                throw new IOException("Wikipedia API returned non-object JSON.");
            }
            Map<String, Object> error = asObject(root.get("error"));
            if (error != null) {
                throw new IOException("Wikipedia API error: " + error);
            }
            return root;
        }

        private void throttle() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestAtMillis;
            if (elapsed < 300L) {
                try {
                    Thread.sleep(300L - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            lastRequestAtMillis = System.currentTimeMillis();
        }

        private static String encodeParams(Map<String, String> params) throws IOException {
            StringBuilder result = new StringBuilder();
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (result.length() > 0) {
                    result.append('&');
                }
                result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                result.append('=');
                result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            return result.toString();
        }

        private static String readAll(InputStream stream) throws IOException {
            if (stream == null) {
                return "";
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                result.append(buffer, 0, read);
            }
            return result.toString();
        }
    }

    static final class CacheStore {
        final Path directory;
        final Map<Integer, PageRecord> pagesById = new TreeMap<Integer, PageRecord>();
        final Map<String, Integer> pageIdsByTitle = new HashMap<String, Integer>();
        final Map<Integer, List<LinkRecord>> linksByPageId = new TreeMap<Integer, List<LinkRecord>>();
        private final Path pagesFile;
        private final Path linksFile;

        CacheStore(Path directory) throws IOException {
            this.directory = directory;
            this.pagesFile = directory.resolve("pages.tsv");
            this.linksFile = directory.resolve("links.tsv");
            Files.createDirectories(directory);
            load();
        }

        PageRecord findPage(String title) {
            Integer pageId = pageIdsByTitle.get(normalize(title));
            return pageId == null ? null : pagesById.get(pageId);
        }

        boolean hasLinks(int pageId) {
            return linksByPageId.containsKey(Integer.valueOf(pageId));
        }

        List<String> getLinks(int pageId) {
            List<LinkRecord> records = linksByPageId.get(Integer.valueOf(pageId));
            if (records == null) {
                return Collections.emptyList();
            }
            List<String> links = new ArrayList<String>();
            for (LinkRecord record : records) {
                links.add(record.toTitle);
            }
            Collections.sort(links, String.CASE_INSENSITIVE_ORDER);
            return links;
        }

        List<PageRecord> pagesWithCachedLinks() {
            List<PageRecord> result = new ArrayList<PageRecord>();
            for (Integer pageId : linksByPageId.keySet()) {
                PageRecord page = pagesById.get(pageId);
                if (page != null) {
                    result.add(page);
                }
            }
            return result;
        }

        void storePageLinks(PageLinks pageLinks) {
            long now = System.currentTimeMillis();
            PageRecord record = new PageRecord(pageLinks.pageId, pageLinks.title, pageLinks.lastRevisionId, now, now);
            pagesById.put(Integer.valueOf(pageLinks.pageId), record);
            pageIdsByTitle.put(normalize(pageLinks.title), Integer.valueOf(pageLinks.pageId));

            List<LinkRecord> records = new ArrayList<LinkRecord>();
            for (String title : pageLinks.links) {
                records.add(new LinkRecord(pageLinks.pageId, title, 0));
            }
            linksByPageId.put(Integer.valueOf(pageLinks.pageId), records);
        }

        void markChecked(int pageId, long lastRevisionId) {
            PageRecord old = pagesById.get(Integer.valueOf(pageId));
            if (old == null) {
                return;
            }
            PageRecord updated = new PageRecord(old.pageId, old.title, lastRevisionId, System.currentTimeMillis(), old.linksCachedAtMillis);
            pagesById.put(Integer.valueOf(pageId), updated);
            pageIdsByTitle.put(normalize(updated.title), Integer.valueOf(updated.pageId));
        }

        void save() throws IOException {
            Files.createDirectories(directory);
            writePages();
            writeLinks();
        }

        private void load() throws IOException {
            if (Files.exists(pagesFile)) {
                BufferedReader reader = Files.newBufferedReader(pagesFile, StandardCharsets.UTF_8);
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().length() == 0 || line.startsWith("#")) {
                            continue;
                        }
                        String[] parts = line.split("\\t", -1);
                        if (parts.length < 5) {
                            continue;
                        }
                        PageRecord page = new PageRecord(
                                Integer.parseInt(parts[0]),
                                decode(parts[1]),
                                Long.parseLong(parts[2]),
                                Long.parseLong(parts[3]),
                                Long.parseLong(parts[4]));
                        pagesById.put(Integer.valueOf(page.pageId), page);
                        pageIdsByTitle.put(normalize(page.title), Integer.valueOf(page.pageId));
                    }
                } finally {
                    reader.close();
                }
            }

            if (Files.exists(linksFile)) {
                BufferedReader reader = Files.newBufferedReader(linksFile, StandardCharsets.UTF_8);
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().length() == 0 || line.startsWith("#")) {
                            continue;
                        }
                        String[] parts = line.split("\\t", -1);
                        if (parts.length < 3) {
                            continue;
                        }
                        int fromPageId = Integer.parseInt(parts[0]);
                        LinkRecord link = new LinkRecord(fromPageId, decode(parts[1]), Integer.parseInt(parts[2]));
                        List<LinkRecord> links = linksByPageId.get(Integer.valueOf(fromPageId));
                        if (links == null) {
                            links = new ArrayList<LinkRecord>();
                            linksByPageId.put(Integer.valueOf(fromPageId), links);
                        }
                        links.add(link);
                    }
                } finally {
                    reader.close();
                }
            }
        }

        private void writePages() throws IOException {
            Path temp = directory.resolve("pages.tsv.tmp");
            BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
            try {
                writer.write("# page_id\ttitle_base64\tlast_revision_id\tlast_checked_millis\tlinks_cached_millis");
                writer.newLine();
                for (PageRecord page : pagesById.values()) {
                    writer.write(Integer.toString(page.pageId));
                    writer.write('\t');
                    writer.write(encode(page.title));
                    writer.write('\t');
                    writer.write(Long.toString(page.lastRevisionId));
                    writer.write('\t');
                    writer.write(Long.toString(page.lastCheckedAtMillis));
                    writer.write('\t');
                    writer.write(Long.toString(page.linksCachedAtMillis));
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
            Files.move(temp, pagesFile, StandardCopyOption.REPLACE_EXISTING);
        }

        private void writeLinks() throws IOException {
            Path temp = directory.resolve("links.tsv.tmp");
            BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
            try {
                writer.write("# from_page_id\tto_title_base64\tnamespace");
                writer.newLine();
                for (Map.Entry<Integer, List<LinkRecord>> entry : linksByPageId.entrySet()) {
                    for (LinkRecord link : entry.getValue()) {
                        writer.write(Integer.toString(link.fromPageId));
                        writer.write('\t');
                        writer.write(encode(link.toTitle));
                        writer.write('\t');
                        writer.write(Integer.toString(link.namespace));
                        writer.newLine();
                    }
                }
            } finally {
                writer.close();
            }
            Files.move(temp, linksFile, StandardCopyOption.REPLACE_EXISTING);
        }

        private static String encode(String value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        private static String decode(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }

    static final class PageRecord {
        final int pageId;
        final String title;
        final long lastRevisionId;
        final long lastCheckedAtMillis;
        final long linksCachedAtMillis;

        PageRecord(int pageId, String title, long lastRevisionId, long lastCheckedAtMillis, long linksCachedAtMillis) {
            this.pageId = pageId;
            this.title = title;
            this.lastRevisionId = lastRevisionId;
            this.lastCheckedAtMillis = lastCheckedAtMillis;
            this.linksCachedAtMillis = linksCachedAtMillis;
        }
    }

    static final class LinkRecord {
        final int fromPageId;
        final String toTitle;
        final int namespace;

        LinkRecord(int fromPageId, String toTitle, int namespace) {
            this.fromPageId = fromPageId;
            this.toTitle = toTitle;
            this.namespace = namespace;
        }
    }

    static final class PageLinks {
        final int pageId;
        final String title;
        final long lastRevisionId;
        final List<String> links;

        PageLinks(int pageId, String title, long lastRevisionId, List<String> links) {
            this.pageId = pageId;
            this.title = title;
            this.lastRevisionId = lastRevisionId;
            this.links = links;
        }
    }

    static final class PageInfo {
        final int pageId;
        final String title;
        final long lastRevisionId;
        final boolean missing;

        PageInfo(int pageId, String title, long lastRevisionId, boolean missing) {
            this.pageId = pageId;
            this.title = title;
            this.lastRevisionId = lastRevisionId;
            this.missing = missing;
        }

        static PageInfo fromJson(Map<String, Object> page) {
            return new PageInfo(
                    intValue(page.get("pageid"), -1),
                    stringValue(page.get("title")),
                    longValue(page.get("lastrevid"), -1L),
                    page.containsKey("missing"));
        }
    }

    static final class Json {
        private final String text;
        private int index;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json parser = new Json(text);
            Object value = parser.readValue();
            parser.skipWhitespace();
            if (parser.index != parser.text.length()) {
                throw new IllegalArgumentException("Unexpected JSON trailing content at " + parser.index);
            }
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }
            char c = text.charAt(index);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            return readNumber();
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return result;
            }
            while (true) {
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                result.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return result;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> result = new ArrayList<Object>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return result;
            }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return result;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return result.toString();
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (index >= text.length()) {
                    throw new IllegalArgumentException("Unexpected end in JSON string escape.");
                }
                char escaped = text.charAt(index++);
                if (escaped == '"' || escaped == '\\' || escaped == '/') {
                    result.append(escaped);
                } else if (escaped == 'b') {
                    result.append('\b');
                } else if (escaped == 'f') {
                    result.append('\f');
                } else if (escaped == 'n') {
                    result.append('\n');
                } else if (escaped == 'r') {
                    result.append('\r');
                } else if (escaped == 't') {
                    result.append('\t');
                } else if (escaped == 'u') {
                    if (index + 4 > text.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape in JSON string.");
                    }
                    String hex = text.substring(index, index + 4);
                    result.append((char) Integer.parseInt(hex, 16));
                    index += 4;
                } else {
                    throw new IllegalArgumentException("Invalid JSON string escape: " + escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string.");
        }

        private Number readNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String number = text.substring(start, index);
            return decimal ? Double.valueOf(number) : Long.valueOf(number);
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void expect(char expected) {
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at JSON position " + index);
            }
            index++;
        }

        private void expect(String expected) {
            if (!text.startsWith(expected, index)) {
                throw new IllegalArgumentException("Expected \"" + expected + "\" at JSON position " + index);
            }
            index += expected.length();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
