package it.dino.switchextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
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
    private static final int REQ_UPD = 4;

    private static final String DEFAULT_OUT = "/storage/emulated/0/SwitchExtract";

    private SharedPreferences prefs;
    private File keysFile;

    // Gioco scelto
    private Uri gameUri;
    private String gameName;
    private long gameSize;
    private String outBase;
    private Uri updUri;
    private String updName;
    private long updSize;
    private TextView btnUpdClear;

    private Extractor extractor;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private boolean running;

    // caratteri
    private Typeface fRegular;
    private Typeface fMedium;
    private Typeface fSemi;
    private Typeface fBold;

    // viste
    private ScrollView page;
    private LinearLayout permBanner;
    private TextView[] stepBadge = new TextView[4];
    private View[] stepLine = new View[3];
    private TextView[] stepValue = new TextView[4];
    private TextView btnRun;
    private LinearLayout progressCard;
    private TextView tvPct;
    private TextView tvPhase;
    private TextView tvStatsLeft;
    private TextView tvStatsRight;
    private TextView tvDetail;
    private TextView tvEta;
    private TextView tvElapsed;
    private PipelineBarView bar;
    private TextView tvCount;
    private TextView btnCancel;
    private TextView tvLog;
    private ScrollView logScroll;
    private String lastPhaseTitle = "";

    // ------------------------------------------------------------------
    // Interfaccia
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        keysFile = new File(getFilesDir(), "prod.keys");
        outBase = prefs.getString("outBase", DEFAULT_OUT);
        fRegular = getResources().getFont(R.font.inter_regular);
        fMedium = getResources().getFont(R.font.inter_medium);
        fSemi = getResources().getFont(R.font.inter_semibold);
        fBold = getResources().getFont(R.font.inter_bold);

        page = new ScrollView(this);
        page.setFillViewport(true);
        page.setClipToPadding(false);
        page.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.graphics.Insets bars = in.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return android.view.WindowInsets.CONSUMED;
            }
        });
        getWindow().setDecorFitsSystemWindows(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = dp(20);
        col.setPadding(side, dp(28), side, dp(32));
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // intestazione
        col.addView(text("Switch Extractor", 30, fBold, R.color.ink, 0));
        TextView sub = text("Estrae romfs ed exefs da un gioco .xci o .nsp, direttamente sul dispositivo.",
                15, fRegular, R.color.ink2, 6);
        sub.setLineSpacing(0, 1.15f);
        col.addView(sub);

        // permesso file
        permBanner = new LinearLayout(this);
        permBanner.setOrientation(LinearLayout.VERTICAL);
        permBanner.setPadding(dp(16), dp(14), dp(16), dp(14));
        permBanner.setBackground(rounded(R.color.surface, 16, R.color.pin, 1));
        TextView pt = text("Serve l'accesso a tutti i file", 15, fSemi, R.color.ink, 0);
        permBanner.addView(pt);
        TextView pd = text("Senza, l'app non puo' leggere il gioco ne' scrivere le cartelle estratte.",
                13, fRegular, R.color.ink2, 2);
        permBanner.addView(pd);
        TextView pb = button("Concedi accesso", false);
        pb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askAllFilesAccess();
            }
        });
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        pbLp.topMargin = dp(10);
        permBanner.addView(pb, pbLp);
        LinearLayout.LayoutParams pbn = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbn.topMargin = dp(20);
        col.addView(permBanner, pbn);

        // passi
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(24);
        col.addView(steps, stLp);

        String[] titles = {"Chiavi della tua Switch", "Gioco base", "Aggiornamento (facoltativo)", "Cartella di output"};
        String[] actions = {"Scegli prod.keys", "Scegli il gioco", "Scegli l'update (.nsp)", "Cambia cartella"};
        final int[] reqs = {REQ_KEYS, REQ_GAME, REQ_UPD, REQ_OUT};
        for (int i = 0; i < 4; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout rail = new LinearLayout(this);
            rail.setOrientation(LinearLayout.VERTICAL);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView badge = new TextView(this);
            badge.setGravity(Gravity.CENTER);
            badge.setTypeface(fSemi);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            badge.setFontFeatureSettings("tnum");
            stepBadge[i] = badge;
            rail.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
            if (i < 3) {
                View line = new View(this);
                stepLine[i] = line;
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(2), 0, 1f);
                ll.topMargin = dp(4);
                ll.bottomMargin = dp(4);
                rail.addView(line, ll);
            }
            row.addView(rail, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(14), 0, 0, i < 3 ? dp(20) : 0);
            body.addView(text(titles[i], 16, fSemi, R.color.ink, 3));
            TextView val = text("", 14, fRegular, R.color.ink2, 2);
            val.setLineSpacing(0, 1.1f);
            stepValue[i] = val;
            body.addView(val);
            TextView act = button(actions[i], false);
            final int req = reqs[i];
            act.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (running) {
                        return;
                    }
                    if (req == REQ_OUT) {
                        Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        launch(in, REQ_OUT);
                    } else {
                        pickFile(req);
                    }
                }
            });
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            alp.topMargin = dp(10);
            if (req == REQ_UPD) {
                LinearLayout acts = new LinearLayout(this);
                acts.setOrientation(LinearLayout.HORIZONTAL);
                acts.addView(act, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
                btnUpdClear = button("Rimuovi", false);
                btnUpdClear.setVisibility(View.GONE);
                btnUpdClear.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!running) {
                            updUri = null;
                            updName = null;
                            updateSteps();
                        }
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
                clp.leftMargin = dp(8);
                acts.addView(btnUpdClear, clp);
                body.addView(acts, alp);
            } else {
                body.addView(act, alp);
            }
            row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            steps.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // azione principale
        btnRun = new TextView(this);
        btnRun.setGravity(Gravity.CENTER);
        btnRun.setText("Estrai romfs ed exefs");
        btnRun.setTypeface(fSemi);
        btnRun.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRunClicked();
            }
        });
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        runLp.topMargin = dp(28);
        col.addView(btnRun, runLp);

        // avanzamento
        progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setPadding(dp(20), dp(18), dp(20), dp(18));
        progressCard.setBackground(rounded(R.color.surface, 20, R.color.line, 1));
        progressCard.setVisibility(View.GONE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        tvPct = text("0%", 44, fBold, R.color.ink, 0);
        tvPct.setFontFeatureSettings("tnum");
        top.addView(tvPct, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvPhase = text("", 14, fMedium, R.color.ink2, 0);
        tvPhase.setGravity(Gravity.END);
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        phLp.leftMargin = dp(12);
        phLp.bottomMargin = dp(8);
        top.addView(tvPhase, phLp);
        progressCard.addView(top);

        bar = new PipelineBarView(this);
        bar.setColors(color(R.color.track), color(R.color.pin), color(R.color.data));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        barLp.topMargin = dp(14);
        progressCard.addView(bar, barLp);

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        tvStatsLeft = text("Pacchetto", 12, fMedium, R.color.pintext, 0);
        tvStatsRight = text("romfs ed exefs", 12, fMedium, R.color.data, 0);
        tvStatsRight.setGravity(Gravity.END);
        legend.addView(tvStatsLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        legend.addView(tvStatsRight, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lgLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lgLp.topMargin = dp(8);
        progressCard.addView(legend, lgLp);

        tvDetail = text("", 13, fRegular, R.color.ink2, 0);
        tvDetail.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtLp.topMargin = dp(10);
        progressCard.addView(tvDetail, dtLp);

        View div = new View(this);
        div.setBackgroundColor(color(R.color.line));
        LinearLayout.LayoutParams dvLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dvLp.topMargin = dp(16);
        dvLp.bottomMargin = dp(14);
        progressCard.addView(div, dvLp);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        tvEta = stat(stats, "Tempo rimasto");
        tvElapsed = stat(stats, "Trascorso");
        tvCount = stat(stats, "File estratti");
        progressCard.addView(stats);
        btnCancel = button("Annulla", true);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (extractor != null) {
                    extractor.cancel();
                }
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        cLp.topMargin = dp(16);
        progressCard.addView(btnCancel, cLp);

        LinearLayout.LayoutParams pcLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pcLp.topMargin = dp(16);
        col.addView(progressCard, pcLp);

        // registro
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(text("Registro", 16, fSemi, R.color.ink, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = button("Copia", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Registro", tvLog.getText()));
                toast("Registro copiato");
            }
        });
        logHead.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        LinearLayout.LayoutParams lhLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lhLp.topMargin = dp(28);
        col.addView(logHead, lhLp);

        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvLog.setTextColor(color(R.color.ink2));
        tvLog.setLineSpacing(0, 1.2f);
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvLog.setHint("Qui compare cosa sta facendo l'app, passo per passo.");
        tvLog.setHintTextColor(color(R.color.ink3));
        logScroll = new ScrollView(this);
        logScroll.setBackground(rounded(R.color.well, 14, R.color.line, 1));
        logScroll.addView(tvLog);
        logScroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            }
        });
        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        lsLp.topMargin = dp(10);
        col.addView(logScroll, lsLp);

        setContentView(page);
        refreshStatus();
    }

    private TextView stat(LinearLayout parent, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        TextView v = text("", 18, fSemi, R.color.ink, 0);
        v.setFontFeatureSettings("tnum");
        c.addView(v);
        c.addView(text(label, 12, fRegular, R.color.ink3, 2));
        parent.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private TextView text(String s, int sp, Typeface tf, int colorRes, int topMarginDp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color(colorRes));
        t.setIncludeFontPadding(false);
        if (topMarginDp > 0) {
            t.setPadding(0, dp(topMarginDp), 0, 0);
        }
        return t;
    }

    /** Pulsante secondario (contorno). destructive = contorno rosso. */
    private TextView button(String label, boolean destructive) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(fSemi);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setTextColor(color(destructive ? R.color.fault : R.color.ink));
        b.setBackground(rounded(R.color.surface, 12, destructive ? R.color.fault : R.color.line, 1));
        b.setClickable(true);
        return b;
    }

    private GradientDrawable rounded(int fillRes, int radiusDp, int strokeRes, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(fillRes));
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            g.setStroke(dp(strokeDp), color(strokeRes));
        }
        return g;
    }

    private int color(int res) {
        return getColor(res);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
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

    private boolean keysLoaded() {
        return keysFile.isFile() && keysFile.length() > 0;
    }

    private void refreshStatus() {
        permBanner.setVisibility(Environment.isExternalStorageManager() ? View.GONE : View.VISIBLE);
        updateSteps();
    }

    /** Aggiorna rotaia, spunte e testi dei tre passi; abilita il pulsante principale. */
    private void updateSteps() {
        boolean[] done = {keysLoaded(), gameUri != null, updUri != null, true};
        String[] vals = {
                keysLoaded() ? prefs.getString("keysName", "Caricata") : "Nessun file scelto",
                gameUri != null ? gameName + (gameSize >= 0 ? " (" + Fmt.bytes(gameSize) + ")" : "") : "Nessun file scelto",
                updUri != null ? updName + (updSize >= 0 ? " (" + Fmt.bytes(updSize) + ")" : "")
                        : "Nessuno: estraggo solo il gioco base",
                outBase + "/<nome del gioco>"
        };
        btnUpdClear.setVisibility(updUri != null ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 4; i++) {
            stepValue[i].setText(vals[i]);
            boolean d = done[i];
            stepBadge[i].setText(d ? "\u2713" : String.valueOf(i + 1));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (d) {
                g.setColor(color(R.color.data));
                stepBadge[i].setTextColor(color(R.color.ondata));
            } else {
                g.setColor(color(R.color.surface));
                g.setStroke(dp(1), color(R.color.line));
                stepBadge[i].setTextColor(color(R.color.ink2));
            }
            stepBadge[i].setBackground(g);
            if (i < 3) {
                stepLine[i].setBackgroundColor(color(d ? R.color.data : R.color.line));
            }
        }
        boolean ready = keysLoaded() && gameUri != null && !running;
        btnRun.setEnabled(ready);
        btnRun.setBackground(rounded(ready ? R.color.pin : R.color.track, 16, R.color.pin, 0));
        btnRun.setTextColor(color(ready ? R.color.onpin : R.color.ink3));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void log(String s) {
        boolean atBottom = logScroll.getChildAt(0).getBottom() - (logScroll.getHeight() + logScroll.getScrollY()) < dp(48);
        tvLog.append(s + "\n");
        if (atBottom) {
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
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
        toast(what + ": " + t.getClass().getSimpleName() + " (dettagli nel registro in basso)");
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
            } else if (requestCode == REQ_UPD) {
                chooseUpdate(uri);
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
        KeyFile.Report rep;
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                // copia in streaming, tenendo solo le righe valide (vedi KeyFile)
                rep = KeyFile.sanitize(in, out);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            tmp.delete();
            throw e;
        } finally {
            in.close();
        }
        if (rep.kept == 0) {
            tmp.delete();
            toast("In \"" + name + "\" non c'e' nessuna chiave valida: e' davvero la prod.keys?");
            return;
        }
        if (keysFile.exists() && !keysFile.delete()) {
            throw new Exception("Impossibile sostituire la prod.keys precedente");
        }
        if (!tmp.renameTo(keysFile)) {
            throw new Exception("Impossibile salvare la prod.keys");
        }
        prefs.edit().putString("keysName", name + " (" + rep.kept + " chiavi)").apply();
        log("prod.keys caricata: " + name + " (" + rep.kept + " chiavi valide)");
        if (!rep.hasMasterKey) {
            log("Attenzione: non trovo nessuna master_key_xx. Questa non sembra una prod.keys completa.");
        }
        if (!rep.dropped.isEmpty()) {
            log("Ho ignorato " + rep.dropped.size() + " riga/e malformata/e (altrimenti hactool si blocca):");
            for (String d : rep.dropped) {
                log("  - " + d);
            }
            log("Se una di queste e' una chiave che serve davvero, l'estrazione fallira' con un errore chiaro.");
        }
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
        updateSteps();
    }

    private void chooseUpdate(Uri uri) {
        String name = displayName(uri);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".nsp")) {
            toast("L'aggiornamento deve essere un file .nsp (hai scelto \"" + name + "\").");
            return;
        }
        updUri = uri;
        updName = name;
        updSize = size(uri);
        updateSteps();
    }

    private void chooseOutput(Uri tree) {
        String path = pathFromDocId(DocumentsContract.getTreeDocumentId(tree), tree.getAuthority());
        if (path == null) {
            toast("Scegli una cartella dalla memoria interna o dalla scheda SD (non da altre app).");
            return;
        }
        outBase = path;
        prefs.edit().putString("outBase", path).apply();
        updateSteps();
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
        String base = outBase == null || outBase.trim().length() == 0 ? DEFAULT_OUT : outBase.trim();

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
        tvCount.setText("0");
        tvEta.setText("in calcolo");
        tvDetail.setText("");
        tvElapsed.setText("00:00");
        tvPct.setText("0%");
        tvPhase.setText("Preparazione");
        bar.setProgress(0.5f, 0f, 0f);
        lastPhaseTitle = "";
        progressCard.setVisibility(View.VISIBLE);
        page.post(new Runnable() {
            @Override
            public void run() {
                page.smoothScrollTo(0, progressCard.getTop() - dp(12));
            }
        });
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

        // aggiornamento, se scelto: stessa logica
        File updInput = null;
        ParcelFileDescriptor updPfd = null;
        if (updUri != null) {
            log("Update: " + updName);
            try {
                File ud = resolveGamePath(updUri);
                if (ud != null) {
                    updInput = ud;
                    log("Leggo l'update da: " + ud);
                } else {
                    updPfd = getContentResolver().openFileDescriptor(updUri, "r");
                    if (updPfd == null) {
                        throw new Exception("Il selettore non ha restituito il file dell'update");
                    }
                    updInput = new File("/proc/" + android.os.Process.myPid() + "/fd/" + updPfd.getFd());
                    log("Percorso dell'update non ricavabile: lo leggo tramite descrittore di file.");
                }
            } catch (Exception e) {
                if (pfd != null) {
                    try {
                        pfd.close();
                    } catch (Exception ignored) {
                        // niente da fare
                    }
                }
                logError("Impossibile aprire l'update", e);
                return;
            }
        }
        final File updFile = updInput;
        final ParcelFileDescriptor holdUpd = updPfd;
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
                        tvCount.setText(String.valueOf(n));
                    }
                });
            }

            @Override
            public void progress(final ProgressModel.Snapshot snap) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showProgress(snap);
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
                    res = ex.run(input, name, updFile, outDir, overwrite);
                } catch (InterruptedException ie) {
                    err = ie;
                } catch (Throwable e) {
                    err = e;
                } finally {
                    if (holdUpd != null) {
                        try {
                            holdUpd.close();
                        } catch (Exception ignored) {
                            // niente da fare
                        }
                    }
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
                            tvPhase.setText("Annullata");
                        } else if (ferr != null) {
                            log("");
                            logError("Estrazione fallita", ferr);
                            tvPhase.setText("Non riuscita");
                        } else {
                            log("");
                            log("Fatto.");
                            tvPhase.setText("Finito");
                            tvPct.setText("100%");
                            bar.setProgress(0.5f, 1f, 1f);
                            tvEta.setText("0 s");
                            log("romfs: " + fres.romfsFiles + " file in " + fres.romfs);
                            log("exefs: " + fres.exefsFiles + " file in " + fres.exefs);
                        }
                    }
                });
            }
        }, "extract");
        worker.start();
    }

    private void setRunning(boolean r) {
        running = r;
        btnCancel.setVisibility(r ? View.VISIBLE : View.GONE);
        updateSteps();
        if (r) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            acquireWakeLock();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            releaseWakeLock();
        }
    }

    private void showProgress(ProgressModel.Snapshot s) {
        if (!running) {
            return; // l'ultimo messaggio puo' arrivare dopo la fine: il riepilogo e' gia' scritto
        }
        tvPct.setText(Math.min(100, (int) (s.overall * 100)) + "%");
        String title;
        switch (s.phase) {
            case ProgressModel.PACKAGE:
                title = "Estraggo i dati dal pacchetto";
                break;
            case ProgressModel.ANALYSIS:
                title = "Cerco il programma principale";
                break;
            case ProgressModel.EXTRACT:
                title = "Decifro romfs ed exefs";
                break;
            default:
                title = "Finito";
        }
        if (!title.equals(lastPhaseTitle)) {
            lastPhaseTitle = title;
            tvPhase.setText(title);
        }
        bar.setProgress(s.share1, s.p1, s.p3);
        tvEta.setText(Fmt.eta(s.etaSec));
        tvElapsed.setText(Fmt.clock(s.elapsedSec));
        String detail = s.totalBytes > 0 ? Fmt.bytesPair(s.doneBytes, s.totalBytes) : "";
        if (s.speedBps > 0) {
            detail += "  " + Fmt.speed(s.speedBps);
        }
        tvDetail.setText(detail);
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
