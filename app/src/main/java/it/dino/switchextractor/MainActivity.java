package it.dino.switchextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_KEYS = 1;
    private static final int REQ_GAME = 2;
    private static final int REQ_OUT = 3;

    private static final String DEFAULT_OUT = "/storage/emulated/0/SwitchExtract";

    private SharedPreferences prefs;
    private File keysFile;

    // Gioco scelto
    private Uri gameUri;
    private String gameName;
    private long gameSize;

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
                pickFile(REQ_KEYS);
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
                pickFile(REQ_GAME);
            }
        });
        root.addView(btnGame);

        root.addView(label("3. Cartella di output"));
        etOut = new EditText(this);
        etOut.setSingleLine(true);
        etOut.setText(prefs.getString("outBase", DEFAULT_OUT));
        root.addView(etOut);
        Button btnOut = new Button(this);
        btnOut.setText("Scegli cartella");
        btnOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                launch(i, REQ_OUT);
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
        tvPerm.setText(ok ? "Accesso ai file: concesso"
                : "Accesso ai file: NON concesso (serve per leggere il gioco e scrivere l'output)");
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

    /** Errore completo (tipo + messaggio + causa + prime righe di stack) nel log, selezionabile e copiabile. */
    private void logError(String what, Throwable t) {
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
        log(sb.toString());
        toast(what + ": " + t.getClass().getSimpleName() + " (dettagli nel log in basso)");
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
    // Selettore di sistema (Storage Access Framework)
    // ------------------------------------------------------------------

    private void pickFile(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        launch(i, req);
    }

    private void launch(Intent i, int req) {
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            logError("Impossibile aprire il selettore file di sistema", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_KEYS) {
                importKeys(uri);
            } else if (requestCode == REQ_GAME) {
                chooseGame(uri);
            } else if (requestCode == REQ_OUT) {
                chooseOutput(uri);
            }
        } catch (Exception e) {
            logError("Selezione file", e);
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) {
                    return n;
                }
            }
        } catch (Exception ignored) {
            // useremo il nome nell'URI
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String last = uri.getLastPathSegment();
        if (last == null) {
            return "file";
        }
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    private long size(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
            // dimensione sconosciuta
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Chiavi: copiate in streaming, nessun percorso e nessun permesso speciale
    // ------------------------------------------------------------------

    private void importKeys(Uri uri) throws Exception {
        String name = displayName(uri);
        long sz = size(uri);
        if (sz > 4L * 1024 * 1024) {
            toast("Quel file e' troppo grande per essere una prod.keys (" + Extractor.human(sz) + ").");
            return;
        }
        File tmp = new File(getFilesDir(), "prod.keys.new");
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("Il selettore non ha restituito il contenuto del file");
        }
        long total = 0;
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 4L * 1024 * 1024) {
                        throw new Exception("File troppo grande per essere una prod.keys");
                    }
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (total == 0) {
            tmp.delete();
            throw new Exception("Il file e' vuoto");
        }
        String content = new String(java.nio.file.Files.readAllBytes(tmp.toPath()), "UTF-8");
        if (!content.contains("master_key")) {
            log("Attenzione: \"" + name + "\" non sembra una prod.keys (manca master_key_xx). La uso lo stesso.");
        }
        if (keysFile.exists() && !keysFile.delete()) {
            throw new Exception("Impossibile sostituire la prod.keys precedente");
        }
        if (!tmp.renameTo(keysFile)) {
            throw new Exception("Impossibile salvare la prod.keys");
        }
        prefs.edit().putString("keysName", name + " (" + total + " byte)").apply();
        log("prod.keys caricata: " + name + " (" + total + " byte)");
        refreshStatus();
    }

    // ------------------------------------------------------------------
    // Gioco e cartella di output
    // ------------------------------------------------------------------

    private void chooseGame(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xci") && !lower.endsWith(".nsp")) {
            toast("Serve un file .xci o .nsp (hai scelto \"" + name + "\").");
            return;
        }
        gameUri = uri;
        gameName = name;
        gameSize = size(uri);
        tvGame.setText(name + (gameSize >= 0 ? "  (" + Extractor.human(gameSize) + ")" : ""));
    }

    private void chooseOutput(Uri tree) {
        String path = pathFromDocId(DocumentsContract.getTreeDocumentId(tree), tree.getAuthority());
        if (path == null) {
            toast("Scegli una cartella dalla memoria interna o dalla scheda SD (non da altre app).");
            return;
        }
        etOut.setText(path);
    }

    /**
     * Converte un document id del provider della memoria ("primary:Download/x.xci",
     * "1234-ABCD:Games") in un percorso vero. null se non e' il provider della memoria.
     */
    private static String pathFromDocId(String docId, String authority) {
        if (docId == null || !"com.android.externalstorage.documents".equals(authority)) {
            return null;
        }
        int colon = docId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String vol = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        String base = "primary".equals(vol) ? "/storage/emulated/0" : "/storage/" + vol;
        return rel.length() == 0 ? base : base + "/" + rel;
    }

    /** Percorso reale del gioco scelto, se ricavabile; altrimenti null (si usera' il descrittore di file). */
    private File resolveGamePath(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                String docId = DocumentsContract.getDocumentId(uri);
                String p = pathFromDocId(docId, uri.getAuthority());
                if (p == null && docId != null && docId.startsWith("raw:")) {
                    p = docId.substring(4);
                }
                if (p != null) {
                    File f = new File(p);
                    if (f.isFile() && f.canRead()) {
                        return f;
                    }
                }
            }
        } catch (Exception ignored) {
            // si passa al fallback
        }
        return null;
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
        if (gameUri == null) {
            toast("Scegli prima il gioco (.xci / .nsp).");
            return;
        }
        String base = etOut.getText().toString().trim();
        if (base.length() == 0) {
            base = DEFAULT_OUT;
        }
        prefs.edit().putString("outBase", base).apply();

        String name = gameName;
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
                    .setMessage("In " + outDir + " esistono gia' romfs/ ed exefs/.\nSe continui verranno cancellate e rifatte da zero.")
                    .setPositiveButton("Sovrascrivi", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
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
        final Uri uri = gameUri;
        final String name = gameName;
        tvLog.setText("");
        tvCount.setText("");
        log("Gioco:  " + name);
        log("Output: " + outDir);

        // 1) percorso diretto, se ricavabile; 2) altrimenti il descrittore di file aperto da questa app
        File direct = resolveGamePath(uri);
        ParcelFileDescriptor pfd = null;
        final File input;
        try {
            if (direct != null) {
                input = direct;
                log("Leggo il gioco da: " + direct);
            } else {
                pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) {
                    throw new Exception("Il selettore non ha restituito il file");
                }
                input = new File("/proc/" + android.os.Process.myPid() + "/fd/" + pfd.getFd());
                log("Percorso non ricavabile: leggo il gioco tramite descrittore di file.");
            }
        } catch (Exception e) {
            logError("Impossibile aprire il gioco", e);
            return;
        }
        log("Non chiudere l'app finche' non finisce.");
        final ParcelFileDescriptor holdPfd = pfd;

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
                Throwable err = null;
                try {
                    res = ex.run(input, name, outDir, overwrite);
                } catch (InterruptedException ie) {
                    err = ie;
                } catch (Throwable e) {
                    err = e;
                } finally {
                    if (holdPfd != null) {
                        try {
                            holdPfd.close();
                        } catch (Exception ignored) {
                            // niente da fare
                        }
                    }
                }
                final Extractor.Result fres = res;
                final Throwable ferr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setRunning(false);
                        if (ferr instanceof InterruptedException) {
                            log("");
                            log("Operazione annullata.");
                        } else if (ferr != null) {
                            log("");
                            logError("Estrazione fallita", ferr);
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
