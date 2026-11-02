package it.dino.switchextractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Logica di estrazione romfs/exefs da .xci / .nsp usando hactool.
 * Nessuna dipendenza da Android: si puo' collaudare con un JDK normale.
 */
public class Extractor {

    public interface Log {
        void line(String s);

        void files(long n);

        /** Avanzamento misurato: percentuale, velocita', tempo stimato. Chiamato ogni ~400 ms. */
        default void progress(ProgressModel.Snapshot s) {
        }
    }

    public static class Result {
        public File romfs;
        public File exefs;
        public long romfsFiles;
        public long exefsFiles;
    }

    /** Un ticket (.tik): rights id + titlekey cifrata, come la vuole hactool. */
    static class Ticket {
        String rightsId;
        String encTitleKey;
        boolean personalized;
    }

    private static class NcaInfo {
        File file;
        String type;
        String titleId;
        String rightsId;
    }

    private static class Out {
        int code;
        List<String> lines = new ArrayList<String>();
    }

    private final File hactool;
    private final File keys;
    private final File homeDir;
    private final Log log;
    private volatile Process current;
    private volatile boolean cancelled;

    // avanzamento
    private volatile ProgressModel model;
    private volatile int phaseNow = ProgressModel.PACKAGE;
    private volatile File watchDir;
    private volatile long p3Done;
    private volatile File p3Current;
    private volatile boolean tickerOn;
    private Thread ticker;

    public Extractor(File hactool, File keys, File homeDir, Log log) {
        this.hactool = hactool;
        this.keys = keys;
        this.homeDir = homeDir;
        this.log = log;
    }

    public void cancel() {
        cancelled = true;
        Process p = current;
        if (p != null) {
            p.destroy();
        }
    }

    // ------------------------------------------------------------------
    // Flusso principale
    // ------------------------------------------------------------------

    public Result run(File input, File outDir, boolean overwrite) throws Exception {
        return run(input, input.getName(), outDir, overwrite);
    }

    /**
     * displayName decide il formato (.xci / .nsp). Serve quando il percorso non ha un'estensione
     * affidabile (per esempio /proc/PID/fd/N ottenuto dal selettore di sistema).
     */
    public Result run(File input, String displayName, File outDir, boolean overwrite) throws Exception {
        return run(input, displayName, null, outDir, overwrite);
    }

    /**
     * Come sopra, ma con un NSP di aggiornamento: romfs ed exefs escono gia' "patchati"
     * (il romfs dell'update e' una differenza rispetto a quello del gioco base, hactool la applica
     * se gli si danno entrambi gli NCA Program).
     */
    public Result run(File input, String displayName, File update, File outDir, boolean overwrite) throws Exception {
        String lower = displayName.toLowerCase(Locale.ROOT);
        boolean xci = lower.endsWith(".xci");
        boolean nsp = lower.endsWith(".nsp");
        if (!xci && !nsp) {
            throw new Exception("Formato non supportato: servono file .xci o .nsp");
        }
        if (!input.isFile()) {
            throw new Exception("File non trovato: " + input);
        }
        if (update != null && !update.isFile()) {
            throw new Exception("File dell'update non trovato: " + update);
        }
        if (!keys.isFile() || keys.length() == 0) {
            throw new Exception("prod.keys non caricata: scegli il file delle chiavi.");
        }
        if (!hactool.isFile()) {
            throw new Exception("Binario hactool non trovato (" + hactool
                    + "). Questo dispositivo e' arm64? L'APK contiene solo la versione arm64.");
        }

        File romfs = new File(outDir, "romfs");
        File exefs = new File(outDir, "exefs");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new Exception("Impossibile creare la cartella di output: " + outDir);
        }
        if (overwrite) {
            deleteRecursive(romfs);
            deleteRecursive(exefs);
        }

        File tmp = new File(outDir, ".tmp_nca");
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) {
            throw new Exception("Impossibile creare la cartella temporanea: " + tmp);
        }

        long free = outDir.getUsableSpace();
        long need = input.length() + (update != null ? update.length() : 0);
        if (free > 0 && free < need) {
            log.line("ATTENZIONE: spazio libero " + human(free) + ", il file e' " + human(need)
                    + ". Serve piu' o meno il doppio del gioco (NCA temporanei + estratti).");
        }

        // quanto lavoro c'e' da fare, letto dalle tabelle del pacchetto (stima se non riesce)
        PackageInfo info = PackageInfo.read(input, xci);
        long w1 = info != null ? info.ncaBytes : input.length();
        long w3 = info != null ? info.largestNca : (long) (w1 * 0.97);
        if (update != null) {
            PackageInfo ui = PackageInfo.read(update, false);
            w1 += ui != null ? ui.ncaBytes : update.length();
        }
        model = new ProgressModel(w1, w3, System.nanoTime());
        phaseNow = ProgressModel.PACKAGE;
        watchDir = tmp;
        p3Done = 0;
        p3Current = null;
        startTicker();

        File baseDir = update != null ? new File(tmp, "base") : tmp;
        File updDir = new File(tmp, "upd");
        try {
            if (update != null && (!baseDir.mkdirs() || !updDir.mkdirs())) {
                throw new Exception("Impossibile creare le cartelle temporanee in " + tmp);
            }
            int n = update != null ? 4 : 3;
            Map<String, String> titleKeys = new HashMap<String, String>();
            Map<String, String> updKeys = new HashMap<String, String>();

            log.line("[1/" + n + "] Estraggo gli NCA " + (xci ? "dall'XCI" : "dall'NSP")
                    + (update != null ? " del gioco base" : "") + "...");
            extractPackage(input, xci, baseDir, titleKeys);
            if (update != null) {
                log.line("[2/4] Estraggo gli NCA dall'NSP dell'update...");
                extractPackage(update, false, updDir, updKeys);
            }

            setPhase(ProgressModel.ANALYSIS);
            log.line("[" + (n - 1) + "/" + n + "] Cerco l'NCA principale (tipo Program)"
                    + (update != null ? ", nel base e nell'update" : "") + "...");
            NcaInfo program = findProgramNca(baseDir);
            NcaInfo patch = null;
            if (update != null) {
                if (program.titleId != null && program.titleId.endsWith("800")) {
                    throw new Exception("Il file scelto come gioco contiene solo un update (Title ID "
                            + program.titleId + "), non il gioco base. Scegli l'XCI/NSP del gioco base.");
                }
                patch = findProgramNca(updDir);
                if (patch.titleId == null || !patch.titleId.endsWith("800")) {
                    throw new Exception("L'NSP scelto come update non contiene un aggiornamento (Title ID "
                            + patch.titleId + "): di solito finisce con 800.");
                }
                if (program.titleId != null && program.titleId.length() == patch.titleId.length()
                        && !program.titleId.substring(0, program.titleId.length() - 3)
                        .equals(patch.titleId.substring(0, patch.titleId.length() - 3))) {
                    throw new Exception("L'update non e' di questo gioco: base " + program.titleId
                            + ", update " + patch.titleId + ".");
                }
                log.line("Update compatibile: base " + program.titleId + ", update " + patch.titleId);
            }

            // titlekey per l'NCA che si estrae davvero (l'update, se c'e')
            NcaInfo target = patch != null ? patch : program;
            Map<String, String> targetKeys = patch != null ? updKeys : titleKeys;
            String titleKey = null;
            if (target.rightsId != null) {
                titleKey = targetKeys.get(target.rightsId);
                if (titleKey == null && (nsp || patch != null)) {
                    throw new Exception("L'NCA principale e' cifrato con titlekey (rights id "
                            + target.rightsId + ") ma nell'NSP non c'e' un ticket utilizzabile.");
                }
            }
            // il base e' letto da hactool tramite title.keys (la sua --titlekey vale solo per l'NCA bersaglio)
            if (patch != null && program.rightsId != null) {
                String baseKey = titleKeys.get(program.rightsId);
                if (baseKey != null) {
                    writeTitleKeys(program.rightsId, baseKey);
                } else {
                    log.line("Nota: anche l'NCA del base usa una titlekey ma non ho un ticket per il base: "
                            + "se l'estrazione fallisce, serve il base come NSP con il suo .tik.");
                }
            }

            p3Done = 0;
            p3Current = null;
            setPhase(ProgressModel.EXTRACT);
            log.line("[" + n + "/" + n + "] Estraggo romfs ed exefs da " + target.file.getName()
                    + (patch != null ? " applicando la patch sul base " + program.file.getName() : "") + " ...");
            List<String> a = new ArrayList<String>();
            if (titleKey != null) {
                a.add("--titlekey=" + titleKey);
            }
            if (patch != null) {
                a.add("--basenca=" + program.file.getAbsolutePath());
            }
            a.add("-t");
            a.add("nca");
            a.add("--romfsdir=" + romfs.getAbsolutePath());
            a.add("--exefsdir=" + exefs.getAbsolutePath());
            a.add(target.file.getAbsolutePath());
            Out o = exec(a, true);

            Result r = new Result();
            r.romfs = romfs;
            r.exefs = exefs;
            r.romfsFiles = countFiles(romfs);
            r.exefsFiles = countFiles(exefs);

            if (o.code != 0 || (r.romfsFiles == 0 && r.exefsFiles == 0)) {
                throw new Exception("Estrazione fallita o vuota (codice " + o.code + ").\n" + tail(o.lines, 15));
            }
            for (String l : o.lines) {
                if (patch != null && l.contains("Base NCA Title ID doesn't match")) {
                    throw new Exception("hactool dice che il base non corrisponde all'update:\n" + l);
                }
                if (l.contains("Unable to match rights id")) {
                    throw new Exception("Titlekey non trovata per questo NCA: i file estratti sarebbero illeggibili.");
                }
            }
            model.finish(System.nanoTime());
            emit();
            return r;
        } finally {
            stopTicker();
            deleteRecursive(tmp);
            new File(new File(homeDir, ".switch"), "title.keys").delete();
        }
    }

    // ------------------------------------------------------------------
    // Ricerca dell'NCA Program (preferisce il gioco base all'update)
    // ------------------------------------------------------------------

    /** Estrae gli NCA (e i ticket) di un .xci / .nsp in dir; per gli NSP riempie titleKeys. */
    private void extractPackage(File pkg, boolean xci, File dir, Map<String, String> titleKeys) throws Exception {
        if (xci) {
            Out o = exec(args("-t", "xci", "--securedir=" + dir.getAbsolutePath(), pkg.getAbsolutePath()), false);
            if (o.code != 0) {
                throw new Exception("Estrazione XCI fallita (codice " + o.code + ").\n" + tail(o.lines, 12)
                        + "\nControlla che prod.keys sia recente quanto il firmware del gioco.");
            }
            return;
        }
        Out o = exec(args("-t", "pfs0", "--outdir=" + dir.getAbsolutePath(), pkg.getAbsolutePath()), false);
        if (o.code != 0) {
            throw new Exception("Estrazione NSP fallita (codice " + o.code + ").\n" + tail(o.lines, 12));
        }
        File[] tiks = dir.listFiles();
        if (tiks != null) {
            for (File f : tiks) {
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".tik")) {
                    Ticket t = parseTicket(f);
                    if (t.personalized) {
                        log.line("Ticket personalizzato ignorato: " + f.getName());
                    } else {
                        titleKeys.put(t.rightsId, t.encTitleKey);
                        log.line("Ticket letto: " + f.getName());
                    }
                }
            }
        }
    }

    /** title.keys nella cartella HOME di hactool: "rights id = titlekey cifrata". */
    private void writeTitleKeys(String rightsId, String encKey) throws IOException {
        File dir = new File(homeDir, ".switch");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Impossibile creare " + dir);
        }
        java.io.FileWriter w = new java.io.FileWriter(new File(dir, "title.keys"));
        try {
            w.write(rightsId + " = " + encKey + "\n");
        } finally {
            w.close();
        }
    }

    private NcaInfo findProgramNca(File dir) throws Exception {
        File[] ncas = dir.listFiles();
        List<File> list = new ArrayList<File>();
        if (ncas != null) {
            for (File f : ncas) {
                if (f.getName().toLowerCase(Locale.ROOT).endsWith(".nca")) {
                    list.add(f);
                }
            }
        }
        if (list.isEmpty()) {
            throw new Exception("Nessun file .nca trovato dentro il pacchetto.");
        }
        File[] arr = list.toArray(new File[0]);
        Arrays.sort(arr, (a, b) -> Long.compare(b.length(), a.length()));

        NcaInfo base = null;
        NcaInfo any = null;
        boolean sawUpdate = false;
        for (File f : arr) {
            Out o = exec(args("-t", "nca", f.getAbsolutePath()), false);
            NcaInfo info = new NcaInfo();
            info.file = f;
            for (String l : o.lines) {
                if (l.startsWith("Content Type:")) {
                    info.type = l.substring("Content Type:".length()).trim();
                } else if (l.startsWith("Title ID:")) {
                    info.titleId = l.substring("Title ID:".length()).trim().toLowerCase(Locale.ROOT);
                } else if (l.startsWith("Rights ID:")) {
                    info.rightsId = hexOnly(l.substring("Rights ID:".length()));
                }
            }
            log.line("  " + f.getName() + " -> " + (info.type == null ? "illeggibile" : info.type)
                    + " (" + human(f.length()) + ")");
            if ("Program".equals(info.type)) {
                if (any == null) {
                    any = info;
                }
                boolean isUpdate = info.titleId != null && info.titleId.endsWith("800");
                if (isUpdate) {
                    sawUpdate = true;
                } else if (base == null) {
                    base = info;
                }
            }
        }
        NcaInfo pick = base != null ? base : any;
        if (pick == null) {
            throw new Exception("Nessun NCA di tipo Program leggibile. Di solito vuol dire che prod.keys e' "
                    + "troppo vecchia per questo gioco (ridumpala con una Lockpick_RCM recente).");
        }
        if (sawUpdate && base != null) {
            log.line("Nota: nel pacchetto c'e' anche un update, viene estratta solo la versione base.");
        }
        return pick;
    }

    // ------------------------------------------------------------------
    // Ticket
    // ------------------------------------------------------------------

    /**
     * Legge un file .tik. Layout: tipo firma (4 byte, big endian), firma, padding,
     * poi il corpo: issuer 0x40, blocco titlekey 0x100, ... tipo titlekey a +0x141,
     * rights id a +0x160.
     */
    static Ticket parseTicket(File f) throws IOException {
        byte[] b = new byte[(int) Math.min(f.length(), 0x10000)];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } finally {
            in.close();
        }
        if (b.length < 8) {
            throw new IOException("Ticket troppo corto: " + f.getName());
        }
        long sig = ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
        int sigSize;
        int pad;
        if (sig == 0x010000L || sig == 0x010003L) {
            sigSize = 0x200;
            pad = 0x3C;
        } else if (sig == 0x010001L || sig == 0x010004L) {
            sigSize = 0x100;
            pad = 0x3C;
        } else if (sig == 0x010002L || sig == 0x010005L) {
            sigSize = 0x3C;
            pad = 0x40;
        } else {
            throw new IOException("Tipo di firma del ticket sconosciuto: 0x" + Long.toHexString(sig));
        }
        int body = 4 + sigSize + pad;
        int keyBlock = body + 0x40;
        int rights = body + 0x160;
        if (b.length < rights + 16) {
            throw new IOException("Ticket troppo corto: " + f.getName());
        }
        Ticket t = new Ticket();
        t.personalized = (b[body + 0x141] & 0xFF) == 1;
        t.encTitleKey = hex(b, keyBlock, 16);
        t.rightsId = hex(b, rights, 16);
        return t;
    }

    // ------------------------------------------------------------------
    // Utilita'
    // ------------------------------------------------------------------

    private List<String> args(String... a) {
        return new ArrayList<String>(Arrays.asList(a));
    }

    private Out exec(List<String> extra, boolean countSaving) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(hactool.getAbsolutePath());
        cmd.add("-k");
        cmd.add(keys.getAbsolutePath());
        cmd.add("--disablekeywarns");
        cmd.addAll(extra);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", homeDir.getAbsolutePath());
        if (cancelled) {
            throw new InterruptedException("Annullato");
        }
        Process p = pb.start();
        current = p;
        Out out = new Out();
        long saved = 0;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Saving ")) {
                        saved++;
                        if (countSaving) {
                            onSaving(SavingLine.path(line));
                        }
                        if (countSaving && saved % 25 == 0) {
                            log.files(saved);
                        }
                        continue;
                    }
                    out.lines.add(line);
                }
            } finally {
                r.close();
            }
            out.code = p.waitFor();
        } finally {
            current = null;
            p.destroy();
        }
        if (cancelled) {
            throw new InterruptedException("Annullato");
        }
        if (countSaving) {
            log.files(saved);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Avanzamento
    // ------------------------------------------------------------------

    private void setPhase(int p) {
        ProgressModel m = model;
        if (m != null) {
            m.setPhase(p, System.nanoTime());
        }
        phaseNow = p;
        emit();
    }

    /** hactool stampa "Saving ..." prima di scrivere ogni file: quello precedente e' quindi completo. */
    private void onSaving(String path) {
        File prev = p3Current;
        if (prev != null) {
            p3Done += prev.length();
        }
        p3Current = path == null ? null : new File(path);
    }

    private void emit() {
        ProgressModel m = model;
        if (m != null) {
            try {
                log.progress(m.snapshot(System.nanoTime()));
            } catch (RuntimeException ignored) {
                // l'interfaccia non deve poter fermare l'estrazione
            }
        }
    }

    private void startTicker() {
        tickerOn = true;
        ticker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (tickerOn) {
                    ProgressModel m = model;
                    if (m != null) {
                        long now = System.nanoTime();
                        int ph = phaseNow;
                        if (ph == ProgressModel.PACKAGE) {
                            m.update(dirBytes(watchDir), now);
                        } else if (ph == ProgressModel.EXTRACT) {
                            File c = p3Current;
                            m.update(p3Done + (c != null ? c.length() : 0), now);
                        }
                        emit();
                    }
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "progress");
        ticker.setDaemon(true);
        ticker.start();
    }

    private void stopTicker() {
        tickerOn = false;
        Thread t = ticker;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                // si esce comunque
            }
        }
    }

    /** Somma dei file direttamente dentro la cartella (gli NCA estratti dal pacchetto). */
    private static long dirBytes(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        long n = 0;
        if (kids != null) {
            for (File k : kids) {
                n += k.isDirectory() ? dirBytes(k) : k.length();
            }
        }
        return n;
    }

    private static String tail(List<String> lines, int n) {
        List<String> real = new ArrayList<String>();
        for (String l : lines) {
            if (!l.startsWith("[WARN]")) {
                real.add(l);
            }
        }
        List<String> use = real.isEmpty() ? lines : real;
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, use.size() - n);
        for (int i = start; i < use.size(); i++) {
            String l = use.get(i);
            if (l.length() > 200) {
                l = l.substring(0, 200) + "...";
            }
            sb.append(l).append('\n');
        }
        return sb.toString().trim();
    }

    static long countFiles(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        long n = 0;
        for (File k : kids) {
            n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        f.delete();
    }

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String hexOnly(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static String human(long bytes) {
        return Fmt.bytes(bytes);
    }
}
