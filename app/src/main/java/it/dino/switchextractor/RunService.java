package it.dino.switchextractor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import java.io.File;

/**
 * Fa il lavoro come servizio in primo piano: continua con lo schermo spento o con l'app chiusa,
 * e mostra l'avanzamento nella notifica.
 */
public class RunService extends Service {

    static final String ACTION_CANCEL = "it.dino.switchextractor.CANCEL";
    private static final String CH_RUN = "run";
    private static final String CH_DONE = "done";
    private static final int ID_RUN = 1;
    private static final int ID_DONE = 2;

    static final class Request {
        Uri game;
        String gameName;
        Uri update;
        String updateName;
        File outDir;
        boolean overwrite;
        File keys;
    }

    private static volatile Request pending;

    static void start(Context c, Request r) {
        pending = r;
        RunHub.get().begin();
        c.startForegroundService(new Intent(c, RunService.class));
    }

    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private long lastNotify;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            RunHub.get().cancel();
            return START_NOT_STICKY;
        }
        final Request r = pending;
        pending = null;
        if (r == null || (worker != null && worker.isAlive())) {
            if (worker == null || !worker.isAlive()) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        createChannels();
        startForeground(ID_RUN, buildRunning("Preparazione", 0, "in calcolo"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchextractor:run");
        wakeLock.acquire(6L * 60L * 60L * 1000L);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    work(r);
                } finally {
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }
        }, "extract");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // ------------------------------------------------------------------

    private void work(Request r) {
        final RunHub hub = RunHub.get();
        hub.line("Gioco:  " + r.gameName);
        if (r.update != null) {
            hub.line("Update: " + r.updateName);
        }
        hub.line("Output: " + r.outDir);

        ParcelFileDescriptor pfd = null;
        ParcelFileDescriptor updPfd = null;
        try {
            File input = DocPaths.resolve(this, r.game);
            if (input != null) {
                hub.line("Leggo il gioco da: " + input);
            } else {
                pfd = getContentResolver().openFileDescriptor(r.game, "r");
                if (pfd == null) {
                    throw new Exception("Il selettore non ha restituito il file");
                }
                input = new File("/proc/" + android.os.Process.myPid() + "/fd/" + pfd.getFd());
                hub.line("Percorso non ricavabile: leggo il gioco tramite descrittore di file.");
            }
            File updFile = null;
            if (r.update != null) {
                updFile = DocPaths.resolve(this, r.update);
                if (updFile != null) {
                    hub.line("Leggo l'update da: " + updFile);
                } else {
                    updPfd = getContentResolver().openFileDescriptor(r.update, "r");
                    if (updPfd == null) {
                        throw new Exception("Il selettore non ha restituito il file dell'update");
                    }
                    updFile = new File("/proc/" + android.os.Process.myPid() + "/fd/" + updPfd.getFd());
                    hub.line("Percorso dell'update non ricavabile: lo leggo tramite descrittore di file.");
                }
            }
            hub.line("L'estrazione continua anche con lo schermo spento: l'avanzamento e' nella notifica.");

            File hactool = new File(getApplicationInfo().nativeLibraryDir, "libhactool.so");
            Extractor ex = new Extractor(hactool, r.keys, getFilesDir(), new Extractor.Log() {
                @Override
                public void line(String s) {
                    hub.line(s);
                }

                @Override
                public void files(long n) {
                    hub.files(n);
                }

                @Override
                public void progress(ProgressModel.Snapshot s) {
                    hub.progress(s);
                    notifyProgress(s);
                }
            });
            hub.setExtractor(ex);
            Extractor.Result res = ex.run(input, r.gameName, updFile, r.outDir, r.overwrite);
            hub.line("");
            hub.line("Fatto.");
            hub.line("romfs: " + res.romfsFiles + " file in " + res.romfs);
            hub.line("exefs: " + res.exefsFiles + " file in " + res.exefs);
            String sum = "romfs: " + res.romfsFiles + " file, exefs: " + res.exefsFiles + " file";
            hub.finish(RunHub.DONE, sum);
            notifyDone("Estrazione completata", sum);
        } catch (InterruptedException ie) {
            hub.line("");
            hub.line("Operazione annullata.");
            hub.finish(RunHub.CANCELLED, "");
        } catch (Throwable e) {
            hub.line("");
            hub.line(RunHub.errorText("Estrazione fallita", e));
            String msg = String.valueOf(e.getMessage());
            int nl = msg.indexOf('\n');
            String first = nl > 0 ? msg.substring(0, nl) : msg;
            hub.finish(RunHub.FAILED, first);
            notifyDone("Estrazione non riuscita", first);
        } finally {
            close(updPfd);
            close(pfd);
        }
    }

    private static void close(ParcelFileDescriptor p) {
        if (p != null) {
            try {
                p.close();
            } catch (Exception ignored) {
                // niente da fare
            }
        }
    }

    // ------------------------------------------------------------------
    // Notifiche
    // ------------------------------------------------------------------

    static String phaseTitle(int phase) {
        switch (phase) {
            case ProgressModel.PACKAGE:
                return "Estraggo i dati dal pacchetto";
            case ProgressModel.ANALYSIS:
                return "Cerco il programma principale";
            case ProgressModel.EXTRACT:
                return "Decifro romfs ed exefs";
            default:
                return "Finito";
        }
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "Estrazione in corso",
                NotificationManager.IMPORTANCE_LOW);
        run.setShowBadge(false);
        nm.createNotificationChannel(run);
        NotificationChannel done = new NotificationChannel(CH_DONE, "Estrazione terminata",
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(done);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildRunning(String title, int pct, String eta) {
        Intent c = new Intent(this, RunService.class).setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 1, c, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(pct + "%  tempo rimasto: " + eta)
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Annulla", cancel).build())
                .build();
    }

    private void notifyProgress(ProgressModel.Snapshot s) {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 1000) {
            return;
        }
        lastNotify = now;
        int pct = Math.min(100, (int) (s.overall * 100));
        getSystemService(NotificationManager.class)
                .notify(ID_RUN, buildRunning(phaseTitle(s.phase), pct, Fmt.eta(s.etaSec)));
    }

    private void notifyDone(String title, String text) {
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(ID_DONE, n);
    }
}
