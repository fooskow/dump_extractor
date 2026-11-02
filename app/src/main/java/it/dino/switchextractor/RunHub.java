package it.dino.switchextractor;

import android.os.Handler;
import android.os.Looper;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Stato condiviso tra il servizio che lavora e la schermata: il servizio scrive, la schermata (se
 * aperta) si iscrive e si ridisegna. Se la schermata viene chiusa e riaperta trova tutto qui.
 */
final class RunHub {

    static final int IDLE = 0;
    static final int RUNNING = 1;
    static final int DONE = 2;
    static final int FAILED = 3;
    static final int CANCELLED = 4;

    interface Listener {
        void onLine(String s);

        void onFiles(long n);

        void onProgress(ProgressModel.Snapshot s);

        void onFinished(int status);
    }

    private static final RunHub INSTANCE = new RunHub();

    static RunHub get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private int status = IDLE;
    private long files;
    private ProgressModel.Snapshot last;
    private Listener listener;
    private Extractor extractor;
    private String summary = "";

    private RunHub() {
    }

    // ---- lettura (schermata)

    synchronized int status() {
        return status;
    }

    synchronized String logText() {
        return log.toString();
    }

    synchronized long files() {
        return files;
    }

    synchronized ProgressModel.Snapshot last() {
        return last;
    }

    synchronized String summary() {
        return summary;
    }

    synchronized void attach(Listener l) {
        listener = l;
    }

    synchronized void detach(Listener l) {
        if (listener == l) {
            listener = null;
        }
    }

    // ---- scrittura (servizio)

    synchronized void begin() {
        log.setLength(0);
        files = 0;
        last = null;
        summary = "";
        status = RUNNING;
    }

    synchronized void setExtractor(Extractor e) {
        extractor = e;
    }

    synchronized void cancel() {
        if (extractor != null) {
            extractor.cancel();
        }
    }

    void line(final String s) {
        final Listener l;
        synchronized (this) {
            log.append(s).append('\n');
            l = listener;
        }
        if (l != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onLine(s);
                }
            });
        }
    }

    void files(final long n) {
        final Listener l;
        synchronized (this) {
            files = n;
            l = listener;
        }
        if (l != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onFiles(n);
                }
            });
        }
    }

    void progress(final ProgressModel.Snapshot s) {
        final Listener l;
        synchronized (this) {
            last = s;
            l = listener;
        }
        if (l != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onProgress(s);
                }
            });
        }
    }

    void finish(final int st, String sum) {
        final Listener l;
        synchronized (this) {
            status = st;
            summary = sum == null ? "" : sum;
            extractor = null;
            l = listener;
        }
        if (l != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    l.onFinished(st);
                }
            });
        }
    }

    /** Errore completo (tipo + messaggio + cause + prime righe di stack), pronto da copiare. */
    static String errorText(String what, Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERRORE: ").append(what).append('\n');
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 4) {
            sb.append(depth == 0 ? "  " : "  causa: ").append(c.getClass().getName())
                    .append(": ").append(String.valueOf(c.getMessage())).append('\n');
            c = c.getCause();
            depth++;
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String[] lines = sw.toString().split("\n");
        for (int i = 1; i < lines.length && i <= 6; i++) {
            sb.append("  ").append(lines[i].trim()).append('\n');
        }
        return sb.toString();
    }
}
