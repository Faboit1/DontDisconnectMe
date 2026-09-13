package top.cheesesmp.ddm.config;

import java.util.Locale;

/** Renders a millisecond duration the way the config asks for. */
public record DurationFormat(
        Style style,
        String suffixDays,
        String suffixHours,
        String suffixMinutes,
        String suffixSeconds,
        boolean trimLeadingZeroUnits,
        int maxUnits) {

    public enum Style {
        /** {@code 1m 23s} */
        SHORT,
        /** {@code 01:23} (or {@code 1:02:03} once hours are involved) */
        CLOCK,
        /** {@code 1 minute, 23 seconds} */
        LONG
    }

    public static DurationFormat defaults() {
        return new DurationFormat(Style.SHORT, "d", "h", "m", "s", true, 2);
    }

    public static DurationFormat from(ConfigSection section) {
        if (section.isEmpty()) {
            return defaults();
        }
        return new DurationFormat(
                section.getEnum(Style.class, "style", Style.SHORT),
                section.getString("suffix-days", "d"),
                section.getString("suffix-hours", "h"),
                section.getString("suffix-minutes", "m"),
                section.getString("suffix-seconds", "s"),
                section.getBoolean("trim-leading-zero-units", true),
                (int) section.getLongClamped("max-units", 2, 1, 4));
    }

    public String format(long millis) {
        return format(millis, style);
    }

    /** {@code mm:ss} regardless of the configured style - used by the *_clock placeholders. */
    public String formatClock(long millis) {
        return format(millis, Style.CLOCK);
    }

    private String format(long millis, Style using) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (using == Style.CLOCK) {
            long clockHours = days * 24 + hours;
            if (clockHours > 0) {
                return String.format(Locale.ROOT, "%d:%02d:%02d", clockHours, minutes, seconds);
            }
            return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
        }

        long[] values = {days, hours, minutes, seconds};
        String[] shortSuffixes = {suffixDays, suffixHours, suffixMinutes, suffixSeconds};
        String[] longNames = {"day", "hour", "minute", "second"};

        StringBuilder out = new StringBuilder();
        int used = 0;
        boolean started = !trimLeadingZeroUnits;
        for (int i = 0; i < values.length; i++) {
            if (!started && values[i] == 0 && i < values.length - 1) {
                continue;
            }
            started = true;
            if (used > 0) {
                out.append(using == Style.LONG ? ", " : " ");
            }
            out.append(values[i]);
            if (using == Style.LONG) {
                out.append(' ').append(longNames[i]);
                if (values[i] != 1) {
                    out.append('s');
                }
            } else {
                out.append(shortSuffixes[i]);
            }
            if (++used >= maxUnits) {
                break;
            }
        }
        return out.length() == 0 ? (using == Style.LONG ? "0 seconds" : "0" + suffixSeconds) : out.toString();
    }
}
