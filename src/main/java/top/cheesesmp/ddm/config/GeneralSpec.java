package top.cheesesmp.ddm.config;

/** Global knobs that are not tied to any one server. */
public record GeneralSpec(boolean debug, long tickIntervalMs, DurationFormat timeFormat) {

    public static GeneralSpec from(ConfigSection section) {
        return new GeneralSpec(
                section.getBoolean("debug", false),
                section.getLongClamped("tick-interval-ms", 250L, 50L, 5000L),
                DurationFormat.from(section.section("time-format")));
    }
}
