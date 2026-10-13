package jcheck.plugins;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A deliberately small Maven resolver: enough to build the classpath for a javac plugin such as
 * Error Prone, NullAway or the Checker Framework, and nothing more.
 *
 * <p>It reads POMs, follows {@code <parent>} chains for properties and
 * {@code <dependencyManagement>}, substitutes {@code ${...}} properties, drops test/provided/
 * optional dependencies, and walks the graph breadth-first. Artifacts are cached under
 * {@code ~/.cache/jcheck/maven}; a local {@code ~/.m2/repository} is used before the network, and
 * {@code offline} mode never reaches out at all.
 *
 * <p>Not implemented on purpose: version ranges, classifiers other than the ones asked for,
 * {@code <dependencyManagement>} imports, and relocation. If one of those actually appears, the
 * resolver reports it as a note rather than silently producing a classpath with a hole in it.
 */
public final class MavenResolver {

    /** A group:artifact:version[:classifier] coordinate. */
    public static final class Gav {
        public final String group;
        public final String artifact;
        public final String version;
        public final String classifier;

        public Gav(String group, String artifact, String version) {
            this(group, artifact, version, "");
        }

        public Gav(String group, String artifact, String version, String classifier) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.classifier = classifier == null ? "" : classifier;
        }

        public static Gav parse(String s) {
            String[] parts = s.trim().split(":");
            if (parts.length < 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new IllegalArgumentException("expected group:artifact:version[:classifier], got '" + s + "'");
            }
            return new Gav(parts[0], parts[1], parts[2], parts.length > 3 ? parts[3] : "");
        }

        String key() {
            return group + ":" + artifact;
        }

        @Override
        public String toString() {
            return key() + ":" + version + (classifier.isEmpty() ? "" : ":" + classifier);
        }
    }

    /** A dependency as declared in a POM. */
    private static final class Dep {
        final String group;
        final String artifact;
        final String version;
        final String scope;
        final boolean optional;
        final String type;
        final String classifier;

        Dep(String group, String artifact, String version, String scope, boolean optional,
            String type, String classifier) {
            this.group = group;
            this.artifact = artifact;
            this.version = version;
            this.scope = scope;
            this.optional = optional;
            this.type = type;
            this.classifier = classifier;
        }

        String key() {
            return group + ":" + artifact;
        }
    }

    private static final class RawPom {
        Gav gav;
        Gav parent;
        String packaging = "jar";
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> depMgmt = new LinkedHashMap<>();
        List<Dep> dependencies = new ArrayList<>();
    }

    private static final class Effective {
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> depMgmt = new LinkedHashMap<>();
        List<Dep> dependencies = new ArrayList<>();
    }

    /** Thrown when a coordinate cannot be resolved and its absence matters. */
    public static final class ResolutionException extends RuntimeException {
        public ResolutionException(String message) {
            super(message);
        }
    }

    private final Path cacheDir;
    private final List<URI> repos;
    private final boolean offline;
    private final Path localM2;
    private final Map<String, RawPom> rawCache = new HashMap<>();
    private final Map<String, Effective> effectiveCache = new HashMap<>();
    private final List<String> notes = new ArrayList<>();
    private final HttpClient http;
    private Path stagingDir;

    public MavenResolver(Path cacheDir, List<URI> repos, boolean offline) {
        this.cacheDir = cacheDir;
        this.repos = new ArrayList<>();
        for (URI r : repos) {
            this.repos.add(r.toString().endsWith("/") ? r : URI.create(r + "/"));
        }
        this.offline = offline;
        String home = System.getProperty("user.home", "");
        this.localM2 = home.isEmpty() ? null : Path.of(home, ".m2", "repository");
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /** The default resolver: Maven Central, cached under the jcheck cache directory. */
    public static MavenResolver standard(boolean offline) {
        return new MavenResolver(defaultCacheDir().resolve("maven"),
            List.of(URI.create("https://repo1.maven.org/maven2/")), offline);
    }

    /** {@code jcheck.cache.dir}, {@code $JCHECK_CACHE_DIR}, or {@code ~/.cache/jcheck}. */
    public static Path defaultCacheDir() {
        String property = System.getProperty("jcheck.cache.dir");
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String env = System.getenv("JCHECK_CACHE_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".cache", "jcheck");
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    /**
     * Resolves each coordinate with its transitive compile/runtime dependencies and returns the
     * jar paths in classpath order. The first occurrence of a group:artifact wins, mirroring
     * Maven's nearest-wins rule for the shapes a javac plugin actually uses.
     */
    public List<Path> resolve(List<String> coordinates) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> roots = new LinkedHashSet<>();
        Deque<Gav> queue = new ArrayDeque<>();
        List<Path> jars = new ArrayList<>();
        for (String c : coordinates) {
            Gav gav = Gav.parse(c);
            roots.add(gav.toString());
            queue.add(gav);
        }
        while (!queue.isEmpty()) {
            Gav gav = queue.poll();
            if (!visited.add(gav.key())) {
                continue;
            }
            try {
                RawPom raw = readPom(gav);
                Effective effective = effective(gav, raw);
                // A few artifacts (SpotBugs among them) declare pom packaging but publish the jar
                // a caller explicitly asked for, so a root coordinate always gets a jar attempt.
                if (!"pom".equals(raw.packaging) || roots.contains(gav.toString())) {
                    Path jar = artifact(gav, "jar", gav.classifier, false);
                    if (jar != null) {
                        jars.add(jar);
                    } else if (!"pom".equals(raw.packaging)) {
                        notes.add("no jar published for " + gav + "; skipped");
                    }
                }
                for (Dep d : effective.dependencies) {
                    if (skipped(d)) {
                        continue;
                    }
                    String group = substitute(d.group, effective.properties);
                    String artifact = substitute(d.artifact, effective.properties);
                    String version = dependencyVersion(d, effective);
                    if (version == null) {
                        notes.add("no version for " + group + ":" + artifact + " (from " + gav + "); skipped");
                        continue;
                    }
                    queue.add(new Gav(group, artifact, version, d.classifier));
                }
            } catch (RuntimeException e) {
                // A missing POM or jar in the transitive graph (often a test-only artifact with no
                // scope) is a hole in that branch, not a reason to abandon the whole classpath.
                notes.add("skipped " + gav + ": " + e.getMessage());
            }
        }
        return jars;
    }

    private boolean skipped(Dep d) {
        if (d.optional) {
            return true;
        }
        if (d.scope == null || d.scope.isEmpty()) {
            return false;
        }
        switch (d.scope) {
            case "test":
            case "provided":
            case "system":
                return true;
            default:
                return false;
        }
    }

    private String dependencyVersion(Dep d, Effective effective) {
        if (d.version != null && !d.version.isBlank()) {
            return substitute(d.version, effective.properties);
        }
        String managed = effective.depMgmt.get(
            substitute(d.group, effective.properties) + ":" + substitute(d.artifact, effective.properties));
        if (managed != null) {
            return substitute(managed, effective.properties);
        }
        return null;
    }

    private Effective effective(Gav gav, RawPom raw) {
        String key = gav.toString();
        Effective cached = effectiveCache.get(key);
        if (cached != null) {
            return cached;
        }
        Effective e = new Effective();
        if (raw.parent != null) {
            RawPom parentRaw = readPom(raw.parent);
            Effective parent = effective(raw.parent, parentRaw);
            e.properties.putAll(parent.properties);
            e.depMgmt.putAll(parent.depMgmt);
            e.dependencies.addAll(parent.dependencies);
        }
        e.properties.putAll(raw.properties);
        e.properties.put("project.groupId", gav.group);
        e.properties.put("project.artifactId", gav.artifact);
        e.properties.put("project.version", gav.version);
        e.properties.put("pom.groupId", gav.group);
        e.properties.put("pom.version", gav.version);
        if (raw.parent != null) {
            e.properties.put("project.parent.version", raw.parent.version);
            e.properties.put("parent.version", raw.parent.version);
        }
        for (Map.Entry<String, String> en : raw.depMgmt.entrySet()) {
            e.depMgmt.put(en.getKey(), substitute(en.getValue(), e.properties));
        }
        e.dependencies.addAll(raw.dependencies);
        effectiveCache.put(key, e);
        return e;
    }

    /** Repeatedly expands {@code ${name}} until it stops changing. */
    private static String substitute(String value, Map<String, String> properties) {
        if (value == null || value.indexOf('$') < 0) {
            return value;
        }
        String out = value;
        for (int pass = 0; pass < 10; pass++) {
            int start = out.indexOf("${");
            if (start < 0) {
                return out;
            }
            StringBuilder sb = new StringBuilder(out.length());
            int i = 0;
            boolean changed = false;
            while (i < out.length()) {
                int s = out.indexOf("${", i);
                if (s < 0) {
                    sb.append(out, i, out.length());
                    break;
                }
                int close = out.indexOf('}', s);
                if (close < 0) {
                    sb.append(out, i, out.length());
                    break;
                }
                String name = out.substring(s + 2, close);
                String replacement = properties.get(name);
                sb.append(out, i, s);
                if (replacement == null) {
                    sb.append(out, s, close + 1);
                } else {
                    sb.append(replacement);
                    changed = true;
                }
                i = close + 1;
            }
            out = sb.toString();
            if (!changed) {
                return out;
            }
        }
        return out;
    }

    private RawPom readPom(Gav gav) {
        RawPom cached = rawCache.get(gav.toString());
        if (cached != null) {
            return cached;
        }
        Path file = fetchFile(pomPath(gav), true);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ResolutionException("cannot read " + file + ": " + e.getMessage());
        }
        RawPom pom = parsePom(gav, bytes);
        rawCache.put(gav.toString(), pom);
        return pom;
    }

    private RawPom parsePom(Gav gav, byte[] bytes) {
        RawPom pom = new RawPom();
        pom.gav = gav;
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new ResolutionException("cannot parse POM for " + gav + ": " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        pom.packaging = text(first(root, "packaging")).orElse("jar");

        Element parent = first(root, "parent");
        if (parent != null) {
            String pg = text(first(parent, "groupId")).orElse("");
            String pa = text(first(parent, "artifactId")).orElse("");
            String pv = text(first(parent, "version")).orElse("");
            if (!pg.isEmpty() && !pa.isEmpty() && !pv.isEmpty()) {
                pom.parent = new Gav(pg, pa, pv);
            }
        }

        Element props = first(root, "properties");
        if (props != null) {
            NodeList children = props.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element) {
                    Element e = (Element) children.item(i);
                    pom.properties.put(e.getTagName(), e.getTextContent().trim());
                }
            }
        }

        Element depMgmt = first(root, "dependencyManagement");
        if (depMgmt != null) {
            for (Element d : elements(depMgmt, "dependency")) {
                String g = text(first(d, "groupId")).orElse("");
                String a = text(first(d, "artifactId")).orElse("");
                String v = text(first(d, "version")).orElse("");
                if (!g.isEmpty() && !a.isEmpty() && !v.isEmpty()) {
                    pom.depMgmt.put(g + ":" + a, v);
                }
            }
        }

        for (Element d : elements(root, "dependency")) {
            /*
             * Only <project><dependencies><dependency> counts. getElementsByTagName also returns
             * dependencies inside <dependencyManagement>, <build><plugins> and <profiles>, and
             * treating a plugin's dependencies as the project's pulls in entire unrelated trees
             * (Maven plugins depend on Maven).
             */
            Element owner = d.getParentNode() instanceof Element ? (Element) d.getParentNode() : null;
            Element grand = owner != null && owner.getParentNode() instanceof Element
                ? (Element) owner.getParentNode() : null;
            if (owner == null || grand == null
                || !"dependencies".equals(owner.getTagName())
                || !"project".equals(grand.getTagName())) {
                continue;
            }
            pom.dependencies.add(new Dep(
                text(first(d, "groupId")).orElse(""),
                text(first(d, "artifactId")).orElse(""),
                text(first(d, "version")).orElse(""),
                text(first(d, "scope")).orElse(""),
                "true".equals(text(first(d, "optional")).orElse("")),
                text(first(d, "type")).orElse("jar"),
                text(first(d, "classifier")).orElse("")));
        }
        return pom;
    }

    private static List<Element> elements(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    private static Element first(Element parent, String name) {
        NodeList list = parent.getElementsByTagName(name);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static Optional<String> text(Element e) {
        return e == null ? Optional.empty() : Optional.of(e.getTextContent().trim());
    }

    private String pomPath(Gav g) {
        return basePath(g) + g.artifact + "-" + g.version + ".pom";
    }

    private String basePath(Gav g) {
        return g.group.replace('.', '/') + "/" + g.artifact + "/" + g.version + "/";
    }

    private Path artifact(Gav g, String extension, String classifier, boolean required) {
        String name = g.artifact + "-" + g.version
            + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier)
            + "." + extension;
        return fetchFile(basePath(g) + name, required);
    }

    /**
     * Finds a file and returns a path that is guaranteed to exist and be readable.
     *
     * <p>Lookup is cache, then {@code ~/.m2}, then the repositories. When the cache cannot be
     * written — a read-only or sandboxed home directory, say — the bytes are staged in a temporary
     * directory instead, because a classpath entry pointing at a file that was never written
     * silently does nothing and produces exactly the false "package does not exist" the plugin was
     * meant to prevent.
     */
    private Path fetchFile(String relative, boolean required) {
        Path cached = cacheDir.resolve(relative);
        if (Files.isRegularFile(cached)) {
            return cached;
        }
        if (localM2 != null) {
            Path local = localM2.resolve(relative);
            if (Files.isRegularFile(local)) {
                return copyToCache(local, cached) ? cached : local;
            }
        }

        // A file: repository is just a directory, so it is readable in offline mode too.
        for (URI repo : repos) {
            if (!"file".equals(repo.getScheme())) {
                continue;
            }
            Path candidate = localRepoFile(repo, relative);
            if (candidate != null) {
                return copyToCache(candidate, cached) ? cached : candidate;
            }
        }

        if (offline) {
            if (required) {
                throw new ResolutionException("offline and " + relative + " is not cached");
            }
            return null;
        }
        for (URI repo : repos) {
            if ("file".equals(repo.getScheme())) {
                continue;
            }
            URI uri;
            try {
                uri = repo.resolve(relative);
            } catch (RuntimeException e) {
                notes.add("repository " + repo + " cannot address " + relative + ": " + e.getMessage());
                continue;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200) {
                    return materialize(relative, cached, res.body());
                }
                if (res.statusCode() != 404) {
                    notes.add("repo " + repo + " returned " + res.statusCode() + " for " + relative);
                }
            } catch (IOException e) {
                notes.add("cannot reach " + repo + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResolutionException("interrupted while downloading " + relative);
            }
        }
        if (required) {
            throw new ResolutionException("cannot resolve " + relative + " from " + repos);
        }
        return null;
    }

    private static Path localRepoFile(URI repo, String relative) {
        try {
            Path candidate = Path.of(repo.resolve(relative));
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean copyToCache(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Writes to the cache if possible, otherwise to a temp directory; both are readable. */
    private Path materialize(String relative, Path cached, byte[] bytes) {
        try {
            Files.createDirectories(cached.getParent());
            Files.write(cached, bytes);
            return cached;
        } catch (IOException e) {
            try {
                if (stagingDir == null) {
                    stagingDir = Files.createTempDirectory("jcheck-maven-");
                }
                Path target = stagingDir.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                return target;
            } catch (IOException e2) {
                throw new ResolutionException("cannot materialize " + relative + ": " + e2.getMessage());
            }
        }
    }

    /** Parses one coordinate without resolving, for tests and diagnostics. */
    public static String normalize(String coordinate) {
        return Gav.parse(coordinate).toString();
    }
}
