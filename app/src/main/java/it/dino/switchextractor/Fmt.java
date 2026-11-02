package it.dino.switchextractor;

import java.util.Locale;

/** Formattazione per l'interfaccia, all'italiana (virgola decimale). */
public final class Fmt {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Fmt() {
    }

    private static int unitFor(double bytes) {
        int i = 0;
        while (bytes >= 1024 && i < UNITS.length - 1) {
            bytes /= 1024;
            i++;
        }
        return i;
    }

    private static String num(double v, int unit) {
        // GB e TB con un decimale; MB e inferiori senza
        return String.format(Locale.ITALIAN, unit >= 3 ? "%.1f" : "%.0f", v);
    }

    /** 16.200.000.000 -> "15,1 GB" */
    public static String bytes(long b) {
        int u = unitFor(b);
        return num(b / Math.pow(1024, u), u) + " " + UNITS[u];
    }

    /** "6,4 di 15,9 GB": la stessa unita' per entrambi, scelta sul totale. */
    public static String bytesPair(long done, long total) {
        int u = unitFor(total);
        double d = Math.pow(1024, u);
        return num(done / d, u) + " di " + num(total / d, u) + " " + UNITS[u];
    }

    public static String speed(double bps) {
        if (bps <= 0) {
            return "in misura";
        }
        return bytes((long) bps) + "/s";
    }

    /** 134 -> "02:14"; 3909 -> "1:05:09" */
    public static String clock(long sec) {
        sec = Math.max(0, sec);
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        return h > 0 ? String.format(Locale.ITALIAN, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ITALIAN, "%02d:%02d", m, s);
    }

    /** 892 -> "14 min 52 s"; 45 -> "45 s"; 3909 -> "1 h 05 min" */
    public static String duration(long sec) {
        sec = Math.max(0, sec);
        if (sec < 60) {
            return sec + " s";
        }
        if (sec < 3600) {
            return (sec / 60) + " min " + (sec % 60) + " s";
        }
        return (sec / 3600) + " h " + String.format(Locale.ITALIAN, "%02d", (sec % 3600) / 60) + " min";
    }

    /** Stima del tempo che manca: -1 = non ancora calcolabile. */
    public static String eta(long sec) {
        if (sec < 0) {
            return "in calcolo";
        }
        if (sec < 60) {
            return "meno di 1 min";
        }
        if (sec < 3600) {
            return "circa " + Math.round(sec / 60.0) + " min";
        }
        long h = sec / 3600;
        long m = Math.round((sec % 3600) / 60.0);
        if (m == 60) {
            h++;
            m = 0;
        }
        return "circa " + h + " h " + String.format(Locale.ITALIAN, "%02d", m) + " min";
    }
}
