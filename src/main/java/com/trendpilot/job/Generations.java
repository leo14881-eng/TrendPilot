package com.trendpilot.job;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class Generations {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private Generations() {
    }

    static String of(String job, Instant asOf) {
        return job + "-" + FMT.format(asOf);
    }
}
