package jcheck.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal argv parser: long flags, {@code --flag=value} and {@code --flag value}, repeatable
 * flags, and positionals. Kept deliberately small so the CLI has no surprises.
 */
public final class Args {

    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final Set<String> present = new LinkedHashSet<>();
    private final List<String> positional = new ArrayList<>();

    private Args() {
    }

    public static Args parse(String[] argv, Set<String> valueFlags) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                String name;
                String inline = null;
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    name = body.substring(0, eq);
                    inline = body.substring(eq + 1);
                } else {
                    name = body;
                }
                a.present.add(name);
                if (inline != null) {
                    a.values.computeIfAbsent(name, k -> new ArrayList<>()).add(inline);
                } else if (valueFlags.contains(name) && i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    a.values.computeIfAbsent(name, k -> new ArrayList<>()).add(argv[++i]);
                } else {
                    a.values.computeIfAbsent(name, k -> new ArrayList<>());
                }
            } else if (arg.length() == 2 && arg.startsWith("-") && arg.charAt(1) != '-') {
                a.present.add(arg.substring(1));
                a.values.computeIfAbsent(arg.substring(1), k -> new ArrayList<>());
            } else {
                a.positional.add(arg);
            }
        }
        return a;
    }

    public boolean has(String name) {
        return present.contains(name);
    }

    public String get(String name) {
        List<String> v = values.get(name);
        return v == null || v.isEmpty() ? null : v.get(v.size() - 1);
    }

    public String get(String name, String def) {
        String v = get(name);
        return v == null ? def : v;
    }

    public int getInt(String name, int def) {
        String v = get(name);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public List<String> getAll(String name) {
        List<String> v = values.get(name);
        return v == null ? List.of() : v;
    }

    /** All values, with each comma-separated element expanded. */
    public List<String> getAllSplit(String name) {
        List<String> out = new ArrayList<>();
        for (String raw : getAll(name)) {
            for (String part : raw.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    public List<String> positional() {
        return positional;
    }
}
