package it.dino.switchextractor;

import java.util.ArrayDeque;

/**
 * Calcola percentuale, velocita' e tempo stimato di un'estrazione in due tratti:
 * il pacchetto (si scrivono gli NCA) e romfs/exefs (si decifra e si scrive).
 * L'analisi che sta in mezzo dura pochi secondi e non ha un tratto suo.
 *
 * Il lavoro e' misurato in byte: il tratto 1 vale quanto gli NCA, il tratto 3 quanto l'NCA
 * principale. La stessa proporzione e' disegnata nella barra, quindi percentuale e barra coincidono.
 * Pura logica, senza Android: l'orologio si passa da fuori (nanosecondi).
 */
public final class ProgressModel {

    public static final int PACKAGE = 1;
    public static final int ANALYSIS = 2;
    public static final int EXTRACT = 3;
    public static final int DONE = 4;

    private static final long SEC = 1000000000L;
    private static final long WINDOW_NS = 10 * SEC;
    private static final double ANALYSIS_GUESS_S = 4;
    private static final double MIN_SPEED = 1024;
    /** Il tratto 3 non arriva a 1 finche' hactool non ha finito: la stima e' sempre un po' alta. */
    private static final double EXTRACT_CAP = 0.995;

    public static final class Snapshot {
        public final int phase;
        public final float overall;   // 0..1, coincide con la barra
        public final float share1;    // quota della barra occupata dal tratto 1
        public final float p1;        // riempimento del tratto 1
        public final float p3;        // riempimento del tratto 3
        public final long doneBytes;
        public final long totalBytes;
        public final double speedBps; // 0 se ancora sconosciuta
        public final long etaSec;     // -1 se ancora sconosciuto
        public final long elapsedSec;

        Snapshot(int phase, float overall, float share1, float p1, float p3, long doneBytes,
                 long totalBytes, double speedBps, long etaSec, long elapsedSec) {
            this.phase = phase;
            this.overall = overall;
            this.share1 = share1;
            this.p1 = p1;
            this.p3 = p3;
            this.doneBytes = doneBytes;
            this.totalBytes = totalBytes;
            this.speedBps = speedBps;
            this.etaSec = etaSec;
            this.elapsedSec = elapsedSec;
        }
    }

    private final long w1;
    private final long w3;
    private final long startNs;
    private int phase = PACKAGE;
    private long phaseStartNs;
    private long done1;
    private long done3;
    private double speed;   // della fase corrente, media mobile
    private double speed1;  // quella misurata nel tratto 1
    private float maxOverall;
    private final ArrayDeque<long[]> samples = new ArrayDeque<long[]>();

    /** w1: byte attesi nel tratto 1; w3: byte attesi nel tratto 3. */
    public ProgressModel(long w1, long w3, long nowNs) {
        this.w1 = Math.max(1, w1);
        this.w3 = Math.max(1, w3);
        this.startNs = nowNs;
        this.phaseStartNs = nowNs;
    }

    public synchronized void setPhase(int p, long nowNs) {
        if (p == phase) {
            return;
        }
        if (phase == PACKAGE && speed > 0) {
            speed1 = speed;
        }
        phase = p;
        phaseStartNs = nowNs;
        samples.clear();
        if (p == EXTRACT) {
            speed = 0;
            done3 = 0;
        }
    }

    /** Byte fatti dall'inizio della fase corrente (ignorato in analisi e a lavoro finito). */
    public synchronized void update(long doneInPhase, long nowNs) {
        long bytes = Math.max(0, doneInPhase);
        if (phase == PACKAGE) {
            done1 = Math.min(bytes, w1);
            bytes = done1;
        } else if (phase == EXTRACT) {
            done3 = Math.min(bytes, (long) (w3 * EXTRACT_CAP));
            bytes = done3;
        } else {
            return;
        }
        samples.addLast(new long[]{nowNs, bytes});
        while (samples.size() > 2 && nowNs - samples.peekFirst()[0] > WINDOW_NS) {
            samples.removeFirst();
        }
        long[] first = samples.peekFirst();
        long dt = nowNs - first[0];
        if (dt >= SEC) {
            double inst = (bytes - first[1]) / (dt / (double) SEC);
            speed = speed <= 0 ? inst : 0.7 * speed + 0.3 * inst;
        }
    }

    public synchronized void finish(long nowNs) {
        setPhase(DONE, nowNs);
    }

    public synchronized Snapshot snapshot(long nowNs) {
        float share1 = (float) ((double) w1 / (w1 + w3));
        float p1 = phase > PACKAGE ? 1f : (float) ((double) done1 / w1);
        float p3 = phase == DONE ? 1f : phase == EXTRACT ? (float) ((double) done3 / w3) : 0f;
        float overall = phase == DONE ? 1f : p1 * share1 + p3 * (1f - share1);
        overall = Math.max(overall, maxOverall);
        maxOverall = overall;

        long done = (phase > PACKAGE ? w1 : done1) + (phase == DONE ? w3 : phase == EXTRACT ? done3 : 0);
        double phaseElapsed = (nowNs - phaseStartNs) / (double) SEC;
        boolean measured = phaseElapsed >= 3 && speed >= MIN_SPEED;

        double eta = -1;
        if (phase == PACKAGE) {
            if (measured) {
                eta = (w1 - done1) / speed + ANALYSIS_GUESS_S + w3 / speed;
            }
        } else if (phase == ANALYSIS) {
            if (speed1 >= MIN_SPEED) {
                eta = Math.max(0, ANALYSIS_GUESS_S - phaseElapsed) + w3 / speed1;
            }
        } else if (phase == EXTRACT) {
            double sp = measured ? speed : speed1;
            if (sp >= MIN_SPEED) {
                eta = (w3 - done3) / sp;
            }
        } else {
            eta = 0;
        }
        long etaSec = eta < 0 ? -1 : Math.min(7L * 24 * 3600, Math.round(eta));
        double shown = (phase == PACKAGE || phase == EXTRACT) && speed >= MIN_SPEED ? speed
                : phase == ANALYSIS || phase == DONE ? speed1 : 0;
        return new Snapshot(phase, overall, share1, p1, p3, done, w1 + w3, shown, etaSec,
                Math.max(0, (nowNs - startNs) / SEC));
    }
}
