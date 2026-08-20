package io.github.tobiaskp.codemap.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for this project: a streaming writer for the HTTP API and a
 * small reader for build manifests like package.json. Keeps the jar dependency-free
 * beyond tree-sitter and sqlite.
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- writing

    /** Appends a JSON string literal, escaped. */
    public static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    public static void field(StringBuilder sb, String key, String value) {
        str(sb, key);
        sb.append(':');
        if (value == null) sb.append("null");
        else str(sb, value);
    }

    public static void field(StringBuilder sb, String key, long value) {
        str(sb, key);
        sb.append(':').append(value);
    }

    public static void field(StringBuilder sb, String key, double value) {
        str(sb, key);
        sb.append(':');
        if (Double.isFinite(value)) sb.append(round(value));
        else sb.append('0');
    }

    /** two decimals is plenty for screen coordinates and keeps payloads small. */
    private static String round(double v) {
        double r = Math.round(v * 100.0) / 100.0;
        if (r == Math.rint(r) && Math.abs(r) < 1e15) return String.valueOf((long) r);
        return String.valueOf(r);
    }

    // ---------------------------------------------------------------- reading

    /** Parses into String / Double / Boolean / null / List / LinkedHashMap. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    public static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String lit, Object val) {
            if (s.startsWith(lit, i)) {
                i += lit.length();
                return val;
            }
            i++;
            return null;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            while (i < s.length() && s.charAt(i) != '}') {
                ws();
                if (s.charAt(i) != '"') break;
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                ws();
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    ws();
                }
            }
            if (i < s.length()) i++; // }
            return m;
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            while (i < s.length() && s.charAt(i) != ']') {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    ws();
                }
            }
            if (i < s.length()) i++; // ]
            return l;
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(e);
                }
            }
            return sb.toString();
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) {
                i++;
                return null;
            }
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
