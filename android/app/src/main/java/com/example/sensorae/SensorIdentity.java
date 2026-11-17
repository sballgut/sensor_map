package com.example.sensorae;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public API IDs and BLE aliases share one canonical identity. */
public final class SensorIdentity {
    public enum Kind { LAB, OPEN_SOURCE, UNKNOWN }
    private static final Pattern LAB = Pattern.compile("sensor\\s*#?\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN = Pattern.compile("opensrc([1-5])", Pattern.CASE_INSENSITIVE);
    private static final int[] ALIASES = {7, 15, 31, 32, 33};
    private SensorIdentity() { }
    public static String normalize(String name) {
        if (name == null) return null;
        String value = name.trim();
        Matcher alias = OPEN.matcher(value);
        if (alias.matches()) return canonical(ALIASES[Integer.parseInt(alias.group(1)) - 1]);
        Matcher lab = LAB.matcher(value);
        if (!lab.matches()) return null;
        int number = Integer.parseInt(lab.group(1));
        return number >= 1 && number <= 34 ? canonical(number) : null;
    }
    public static Kind kind(String name) {
        if (normalize(name) == null) return Kind.UNKNOWN;
        return OPEN.matcher(name.trim()).matches() ? Kind.OPEN_SOURCE : Kind.LAB;
    }
    private static String canonical(int number) { return String.format(Locale.US, "sensor %02d", number); }
}
