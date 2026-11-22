package io.policynim.policy.catalog;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

final class DeclaredSourceDate {

    // Pydantic also accepts a short clock, fractional seconds and hour/minute offsets through 23:59.
    private static final Pattern CALENDAR = Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2})"
        + "(?:[Tt _]00:00(?::00(?:[.,]([0-9]+))?)?(?:[Zz]|[+\\-\\u2212](?:[01][0-9]|2[0-3]):?[0-5][0-9])?)?");
    private static final Pattern EPOCH = Pattern.compile("[+-]?(?:[0-9]+|(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final long SECONDS_PER_DAY = 86_400;

    private DeclaredSourceDate() {
    }

    static LocalDate read(PolicyPackFields fields) {
        Object value = fields.value("retrieved_on");
        if (value == null) {
            return null;
        }
        try {
            LocalDate date = convert(value);
            if (date != null && date.getYear() >= 1 && date.getYear() <= 9999) {
                return date;
            }
        } catch (DateTimeException | NumberFormatException ex) {
            // Report the field without the supplied timestamp.
        }
        fields.error("retrieved_on", "Expected a valid date or a datetime at local midnight.");
        return null;
    }

    private static LocalDate convert(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof OffsetDateTime offset) {
            value = offset.toLocalDateTime();
        }
        if (value instanceof LocalDateTime datetime) {
            // Python's TOML datetime truncates to microsecond precision.
            return datetime.toLocalTime().toNanoOfDay() < 1000 ? datetime.toLocalDate() : null;
        }
        if (value instanceof Number number) {
            return fromEpoch(number.doubleValue());
        }
        if (value instanceof String text) {
            var match = CALENDAR.matcher(text);
            if (match.matches()) {
                String fraction = match.group(2);
                if (fraction != null && fraction.chars().limit(6).anyMatch(digit -> digit != '0')) {
                    return null;
                }
                return LocalDate.parse(match.group(1));
            }
            if (EPOCH.matcher(text).matches()) {
                return fromEpoch(Double.parseDouble(text));
            }
        }
        return null;
    }

    private static LocalDate fromEpoch(double timestamp) {
        double seconds = Math.abs(timestamp) > 20_000_000_000L ? timestamp / 1000 : timestamp;
        if (!Double.isFinite(seconds) || seconds < -62_135_596_801L || seconds > 253_402_300_800L) {
            return null;
        }
        long whole = (long) Math.floor(seconds);
        long micros = Math.round((seconds - whole) * 1_000_000);
        if (micros == 1_000_000) {
            whole++;
        } else if (micros != 0) {
            return null;
        }
        return whole % SECONDS_PER_DAY == 0 ? LocalDate.ofEpochDay(whole / SECONDS_PER_DAY) : null;
    }
}
