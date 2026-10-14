package com.marketingpanel.common;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** All timestamps are stored in UTC and displayed in IST. */
public final class Time {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private Time() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static LocalDate todayIst() {
        return LocalDate.now(IST);
    }

    public static LocalDateTime toIst(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(IST).toLocalDateTime();
    }

    public static LocalDateTime istToUtc(LocalDateTime ist) {
        return ist.atZone(IST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** Start of the given IST day expressed in UTC. */
    public static LocalDateTime startOfIstDayUtc(LocalDate d) {
        return istToUtc(d.atStartOfDay());
    }

    public static LocalDateTime startOfTodayIstUtc() {
        return startOfIstDayUtc(todayIst());
    }

    public static String fmt(LocalDateTime utc) {
        return utc == null ? "" : toIst(utc).format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")) + " IST";
    }

    /** Parses an ISO instant ("2026-10-20T10:00:00Z") or local date-time as UTC. */
    public static LocalDateTime parseUtc(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.parse(s);
        }
    }
}
