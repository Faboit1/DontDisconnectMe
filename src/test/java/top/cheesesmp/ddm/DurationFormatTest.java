package top.cheesesmp.ddm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import top.cheesesmp.ddm.config.DurationFormat;

class DurationFormatTest {

    private static DurationFormat style(DurationFormat.Style style, int maxUnits) {
        return new DurationFormat(style, "d", "h", "m", "s", true, maxUnits);
    }

    @Test
    void shortStyleTrimsLeadingZeroUnits() {
        DurationFormat format = style(DurationFormat.Style.SHORT, 2);
        assertEquals("23s", format.format(23_000L));
        assertEquals("1m 23s", format.format(83_000L));
        assertEquals("1h 1m", format.format(3_660_000L), "only two units are shown");
    }

    @Test
    void clockStyleAlwaysPadsMinutesAndSeconds() {
        DurationFormat format = style(DurationFormat.Style.CLOCK, 2);
        assertEquals("00:05", format.format(5_000L));
        assertEquals("01:23", format.format(83_000L));
        assertEquals("1:01:00", format.format(3_660_000L));
    }

    @Test
    void longStylePluralisesProperly() {
        DurationFormat format = style(DurationFormat.Style.LONG, 2);
        assertEquals("1 minute, 1 second", format.format(61_000L));
        assertEquals("2 minutes, 5 seconds", format.format(125_000L));
    }

    @Test
    void clockPlaceholderIgnoresTheConfiguredStyle() {
        DurationFormat format = style(DurationFormat.Style.SHORT, 2);
        assertEquals("1m 23s", format.format(83_000L));
        assertEquals("01:23", format.formatClock(83_000L));
    }

    @Test
    void zeroAndNegativeAreSafe() {
        DurationFormat format = style(DurationFormat.Style.SHORT, 2);
        assertEquals("0s", format.format(0L));
        assertEquals("0s", format.format(-5_000L));
    }
}
