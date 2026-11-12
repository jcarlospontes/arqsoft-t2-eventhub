package br.uece.eventhub.registration;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

final class Log {
    private Log() { }

    static void info(String msg) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.MILLIS) + " [registration] " + msg);
    }

    static void corr(String corr, String msg) {
        info("corr=" + corr + " " + msg);
    }
}
