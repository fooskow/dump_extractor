package it.dino.switchextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private interface PathCallback {
        void picked(File f);
    }

    private static final String DEFAULT_OUT = "/storage/emulated/0/SwitchExtract";

    private SharedPreferences prefs;
    private File keysFile;
    private File inputFile;
    private Extractor extractor;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    private ScrollView scroll;
    private TextView tvPerm;
    private Button btnPerm;
    private TextView tvKeys;
    private TextView tvGame;
    private EditText etOut;
    private Button btnRun;
    private Button btnCancel;
    private ProgressBar progress;
    private TextView tvCount;
    private TextView tvLog;

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        keysFile = new File(getFilesDir(), "prod.keys");

        scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Switch Extractor");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Estrae romfs ed exefs da un file .xci o .nsp (usa hactool).");
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        tvPerm = new TextView(this);
        root.addView(tvPerm);
        btnPerm = new Button(this);
        btnPerm.setText("Concedi accesso a tutti i file");
        btnPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askAllFilesAccess();
            }
        });
        root.addView(btnPerm);

        root.addView(label("1. Chiavi della tua Switch"));
        tvKeys = new TextView(this);
        root.addView(tvKeys);
        Button btnKeys = new Button(this);
        btnKeys.setText("Scegli prod.keys");
        btnKeys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ensureAccess()) {
                    return;
                }
                browse(startDir(), false, new String[]{""}, new PathCallback() {
                    @Override
                    public void picked(File f) {
                        importKeys(f);
                    }
                });
            }
        });
        root.addView(btnKeys);

        root.addView(label("2. Gioco (.xci / .nsp)"));
        tvGame = new TextView(this);
        tvGame.setText("Nessun file scelto");
        root.addView(tvGame);
        Button btnGame = new Button(this);
        btnGame.setText("Scegli il gioco");
        btnGame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ensureAccess()) {
                    return;
                }
                browse(startDir(), false, new String[]{".xci", ".nsp"}, new PathCallback() {
                    @Override
                    public void picked(File f) {
                        inputFile = f;
                        prefs.edit().putString("lastDir", f.getParent()).apply();
                        tvGame.setText(f.getName() + "  (" + Extractor.human(f.length()) + ")");
                    }
                });
            }
        });
        root.addView(btnGame);

        root.addView(label("3. Cartella di output"));
        etOut = new EditText(this);
        etOut.setSingleLine(true);
        etOut.setText(prefs.getString("outBase", DEFAULT_OUT));
        root.addView(etOut);
        Button btnOut = new Button(this);
        btnOut.setText("Sfoglia cartella");
        btnOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ensureAccess()) {
                    return;
                }
                browse(startDir(), true, null, new PathCallback() {
                    @Override
                    public void picked(File f) {
                        etOut.setText(f.getAbsolutePath());
                    }
                });
            }
        });
        root.addView(btnOut);

        TextView hint = new TextView(this);
        hint.setText("Dentro la cartella viene creata una sottocartella col nome del gioco, con romfs/ ed exefs/.");
        hint.setTextSize(12);
        hint.setPadding(0, dp(4), 0, dp(12));
        root.addView(hint);

        btnRun = new Button(this);
        btnRun.setText("ESTRAI");
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        root.addView(btnRun);

        btnCancel = new Button(this);
        btnCancel.setText("Annulla");
        btnCancel.setEnabled(false);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (extractor != null) {
                    extractor.cancel();
                }
            }
        });
        root.addView(btnCancel);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        tvCount = new TextView(this);
        root.addView(tvCount);

        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(12);
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(0, dp(8), 0, 0);
        root.addView(tvLog);

        setContentView(scroll);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (extractor != null && worker != null && worker.isAlive()) {
            extractor.cancel();
        }
        releaseWakeLock();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshStatus() {
        boolean ok = Environment.isExternalStorageManager();
        tvPerm.setText(ok ? "Accesso ai file: concesso" : "Accesso ai file: NON concesso (serve per leggere il gioco e scrivere l'output)");
        btnPerm.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (keysFile.isFile() && keysFile.length() > 0) {
            tvKeys.setText("prod.keys caricata: " + prefs.getString("keysName", "ok"));
        } else {
            tvKeys.setText("Nessuna prod.keys caricata");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void log(String s) {
        tvLog.append(s + "\n");
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ------------------------------------------------------------------
    // Permessi
    // ------------------------------------------------------------------

    private boolean ensureAccess() {
        if (Environment.isExternalStorageManager()) {
            return true;
        }
        toast("Prima concedi l'accesso a tutti i file.");
        askAllFilesAccess();
        return false;
    }

    private void askAllFilesAccess() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ------------------------------------------------------------------
    // Selettore di file / cartelle (nessun Storage Access Framework:
    // hactool ha bisogno di percorsi veri)
    // ------------------------------------------------------------------

    private File startDir() {
        String last = prefs.getString("lastDir", null);
        if (last != null && new File(last).isDirectory()) {
            return new File(last);
        }
        File dl = new File("/storage/emulated/0/Download");
        if (dl.isDirectory()) {
            return dl;
        }
        return new File("/storage/emulated/0");
    }

    private List<File> roots() {
        List<File> r = new ArrayList<File>();
        r.add(new File("/storage/emulated/0"));
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                if (v.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}") && v.isDirectory()) {
                    r.add(v);
                }
            }
        }
        return r;
    }

    private boolean isRoot(File d) {
        for (File r : roots()) {
            if (r.getAbsolutePath().equals(d.getAbsolutePath())) {
                return true;
            }
        }
        return false;
    }

    /** dir == null mostra l'elenco delle memorie. exts == null: solo cartelle. */
    private void browse(final File dir, final boolean dirMode, final String[] exts, final PathCallback cb) {
        final List<String> labels = new ArrayList<String>();
        final List<File> targets = new ArrayList<File>();
        AlertDialog.Builder b = new AlertDialog.Builder(this);

        if (dir == null) {
            b.setTitle("Scegli la memoria");
            for (File r : roots()) {
                labels.add("[memoria] " + (r.getAbsolutePath().startsWith("/storage/emulated") ? "Interna" : "Scheda SD " + r.getName()));
                targets.add(r);
            }
        } else {
            File[] kids = dir.listFiles();
            if (kids == null) {
                toast("Cartella non leggibile: " + dir);
                browse(null, dirMode, exts, cb);
                return;
            }
            b.setTitle(dir.getAbsolutePath());
            labels.add("[su] ..");
            targets.add(isRoot(dir) ? null : dir.getParentFile());

            List<File> dirs = new ArrayList<File>();
            List<File> files = new ArrayList<File>();
            for (File k : kids) {
                if (k.getName().startsWith(".")) {
                    continue;
                }
                if (k.isDirectory()) {
                    dirs.add(k);
                } else if (!dirMode && matches(k, exts)) {
                    files.add(k);
                }
            }
            Comparator<File> byName = new Comparator<File>() {
                @Override
                public int compare(File a, File c) {
                    return a.getName().compareToIgnoreCase(c.getName());
                }
            };
            Collections.sort(dirs, byName);
            Collections.sort(files, byName);
            for (File d : dirs) {
                labels.add("[cartella] " + d.getName());
                targets.add(d);
            }
            for (File f : files) {
                labels.add(f.getName() + "  (" + Extractor.human(f.length()) + ")");
                targets.add(f);
            }
        }

        b.setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface d, int which) {
                File t = targets.get(which);
                if (t == null) {
                    browse(null, dirMode, exts, cb);
                } else if (t.isDirectory()) {
                    browse(t, dirMode, exts, cb);
                } else {
                    cb.picked(t);
                }
            }
        });
        if (dirMode && dir != null) {
            b.setPositiveButton("Scegli questa cartella", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int which) {
                    cb.picked(dir);
                }
            });
        }
        b.setNegativeButton("Annulla", null);
        b.show();
    }

    private static boolean matches(File f, String[] exts) {
        if (exts == null) {
            return false;
        }
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (String e : exts) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Chiavi
    // ------------------------------------------------------------------

    private void importKeys(File src) {
        try {
            if (src.length() > 4 * 1024 * 1024) {
                toast("Quel file e' troppo grande per essere una prod.keys.");
                return;
            }
            InputStream in = new FileInputStream(src);
            OutputStream out = new FileOutputStream(keysFile);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                in.close();
                out.close();
            }
            String content = new String(java.nio.file.Files.readAllBytes(keysFile.toPath()), "UTF-8");
            if (!content.contains("master_key")) {
                toast("Attenzione: il file non sembra una prod.keys (manca master_key_xx).");
            }
            prefs.edit().putString("keysName", src.getName()).putString("lastDir", src.getParent()).apply();
            refreshStatus();
        } catch (Exception e) {
            toast("Errore nel leggere le chiavi: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Estrazione
    // ------------------------------------------------------------------

    private void onRunClicked() {
        if (!ensureAccess()) {
            return;
        }
        if (!keysFile.isFile() || keysFile.length() == 0) {
            toast("Scegli prima la prod.keys.");
            return;
        }
        if (inputFile == null) {
            toast("Scegli prima il gioco (.xci / .nsp).");
            return;
        }
        String base = etOut.getText().toString().trim();
        if (base.length() == 0) {
            base = DEFAULT_OUT;
        }
        prefs.edit().putString("outBase", base).apply();

        String name = inputFile.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        final File outDir = new File(base, name);

        File romfs = new File(outDir, "romfs");
        File exefs = new File(outDir, "exefs");
        boolean exists = (romfs.isDirectory() && Extractor.countFiles(romfs) > 0)
                || (exefs.isDirectory() && Extractor.countFiles(exefs) > 0);
        if (exists) {
            new AlertDialog.Builder(this)
                    .setTitle("Cartelle gia' presenti")
                    .setMessage("In " + outDir + " esistono gia' romfs/ed exefs/.\nSe continui verranno cancellate e rifatte da zero.")
                    .setPositiveButton("Sovrascrivi", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            startRun(outDir, true);
                        }
                    })
                    .setNegativeButton("Annulla", null)
                    .show();
        } else {
            startRun(outDir, false);
        }
    }

    private void startRun(final File outDir, final boolean overwrite) {
        final File hactool = new File(getApplicationInfo().nativeLibraryDir, "libhactool.so");
        final File input = inputFile;
        tvLog.setText("");
        tvCount.setText("");
        log("Gioco:  " + input.getName());
        log("Output: " + outDir);
        log("Non chiudere l'app finche' non finisce.");

        extractor = new Extractor(hactool, keysFile, getFilesDir(), new Extractor.Log() {
            @Override
            public void line(final String s) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        log(s);
                    }
                });
            }

            @Override
            public void files(final long n) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvCount.setText("File estratti: " + n);
                    }
                });
            }
        });

        setRunning(true);
        final Extractor ex = extractor;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                Extractor.Result res = null;
                String err = null;
                try {
                    res = ex.run(input, outDir, overwrite);
                } catch (InterruptedException ie) {
                    err = "Operazione annullata.";
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final Extractor.Result fres = res;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setRunning(false);
                        if (ferr != null) {
                            log("");
                            log("ERRORE: " + ferr);
                        } else {
                            log("");
                            log("FATTO.");
                            log("romfs: " + fres.romfsFiles + " file -> " + fres.romfs);
                            log("exefs: " + fres.exefsFiles + " file -> " + fres.exefs);
                        }
                    }
                });
            }
        }, "extract");
        worker.start();
    }

    private void setRunning(boolean running) {
        btnRun.setEnabled(!running);
        btnCancel.setEnabled(running);
        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            acquireWakeLock();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            releaseWakeLock();
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchextractor:run");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
