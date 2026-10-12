package jcheck.util;

/**
 * Minimal streaming JSON writer. No dependencies, no reflection, no configuration: just enough
 * to emit the versioned schemas jcheck promises.
 */
public final class JsonWriter {

    private final StringBuilder sb = new StringBuilder();
    private final java.util.ArrayDeque<Boolean> firstStack = new java.util.ArrayDeque<>();
    private boolean afterName = false;

    public JsonWriter beginObject() {
        beforeValue();
        sb.append('{');
        firstStack.push(Boolean.TRUE);
        return this;
    }

    public JsonWriter endObject() {
        sb.append('}');
        firstStack.pop();
        return this;
    }

    public JsonWriter beginArray() {
        beforeValue();
        sb.append('[');
        firstStack.push(Boolean.TRUE);
        return this;
    }

    public JsonWriter endArray() {
        sb.append(']');
        firstStack.pop();
        return this;
    }

    public JsonWriter name(String n) {
        if (afterName) {
            throw new IllegalStateException("name() without an intervening value");
        }
        beforeElement();
        sb.append(quote(n)).append(':');
        afterName = true;
        return this;
    }

    public JsonWriter value(String v) {
        beforeValue();
        sb.append(v == null ? "null" : quote(v));
        return this;
    }

    public JsonWriter value(long v) {
        beforeValue();
        sb.append(v);
        return this;
    }

    public JsonWriter value(boolean v) {
        beforeValue();
        sb.append(v);
        return this;
    }

    public JsonWriter nullValue() {
        beforeValue();
        sb.append("null");
        return this;
    }

    /** Emits {@code name: value} in one call, skipping nothing. */
    public JsonWriter field(String n, String v) {
        name(n);
        return value(v);
    }

    public JsonWriter field(String n, long v) {
        name(n);
        return value(v);
    }

    public JsonWriter field(String n, boolean v) {
        name(n);
        return value(v);
    }

    /** Emits the field only when the value is non-null. */
    public JsonWriter optionalField(String n, String v) {
        if (v != null) {
            name(n);
            value(v);
        }
        return this;
    }

    private void beforeValue() {
        if (!afterName) {
            beforeElement();
        }
        afterName = false;
    }

    private void beforeElement() {
        if (firstStack.isEmpty()) {
            return;
        }
        if (Boolean.TRUE.equals(firstStack.peek())) {
            firstStack.pop();
            firstStack.push(Boolean.FALSE);
        } else {
            sb.append(',');
        }
    }

    public boolean isEmpty() {
        return sb.length() == 0;
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    /** A JSON string literal, including the surrounding quotes. */
    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }
}
