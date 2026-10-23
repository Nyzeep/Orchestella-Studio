package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

/**
 * Connection counters. Deliberately no token, argv, source, object bytes or
 * capability values; only numbers and a fixed terminal code.
 */
public record Axp1Stats(
        long dataBytesIn,
        long dataBytesOut,
        long framesRead,
        long framesWritten,
        long pings,
        long pongs,
        long bufferedBytes,
        long durationMillis,
        Axp1Error terminal) {
    @Override
    public String toString() {
        return "Axp1Stats[in=" + dataBytesIn + ",out=" + dataBytesOut
                + ",framesIn=" + framesRead + ",framesOut=" + framesWritten
                + ",pings=" + pings + ",pongs=" + pongs
                + ",buffered=" + bufferedBytes + ",ms=" + durationMillis
                + ",terminal=" + terminal + "]";
    }
}
