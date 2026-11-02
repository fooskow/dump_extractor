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
        String lower = displayName.toLowerCase(Locale.ROOT);
        boolean xci = lower.endsWith(".xci");
        boolean nsp = lower.endsWith(".nsp");
        if (!xci && !nsp) {
            throw new Exception("Formato non supportato: servono file .xci o .nsp");
        }
        if (!input.isFile()) {
            throw new Exception("File non trovato: " + input);
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
        long need = input.length();
        if (free > 0 && free < need) {
            log.line("ATTENZIONE: spazio libero " + human(free) + ", il file e' " + human(need)
                    + ". Serve piu' o meno il doppio del gioco (NCA temporanei + estratti).");
        }

        try {
            Map<String, String> titleKeys = new HashMap<String, String>();

            if (xci) {
                log.line("[1/3] Estraggo gli NCA dall'XCI...");
                Out o = exec(args("-t", "xci", "--securedir=" + tmp.getAbsolutePath(),
                        input.getAbsolutePath()), false);
                if (o.code != 0) {
                    throw new Exception("Estrazione XCI fallita (codice " + o.code + ").\n" + tail(o.lines, 12)
                            + "\nControlla che prod.keys sia recente quanto il firmware del gioco.");
                }
            } else {
                log.line("[1/3] Estraggo gli NCA dall'NSP...");
                Out o = exec(args("-t", "pfs0", "--outdir=" + tmp.getAbsolutePath(),
                        input.getAbsolutePath()), false);
                if (o.code != 0) {
                    throw new Exception("Estrazione NSP fallita (codice " + o.code + ").\n" + tail(o.lines, 12));
                }
                File[] tiks = tmp.listFiles();
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

            log.line("[2/3] Cerco l'NCA principale (tipo Program)...");
            NcaInfo program = findProgramNca(tmp);
            String titleKey = null;
            if (program.rightsId != null) {
                titleKey = titleKeys.get(program.rightsId);
                if (titleKey == null && nsp) {
                    throw new Exception("L'NCA principale e' cifrato con titlekey (rights id "
                            + program.rightsId + ") ma nell'NSP non c'e' un ticket utilizzabile.");
                }
            }

            log.line("[3/3] Estraggo romfs ed exefs da " + program.file.getName() + " ...");
            List<String> a = new ArrayList<String>();
            if (titleKey != null) {
                a.add("--titlekey=" + titleKey);
            }
            a.add("-t");
            a.add("nca");
            a.add("--romfsdir=" + romfs.getAbsolutePath());
            a.add("--exefsdir=" + exefs.getAbsolutePath());
            a.add(program.file.getAbsolutePath());
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
                if (l.contains("Unable to match rights id")) {
                    throw new Exception("Titlekey non trovata per questo NCA: i file estratti sarebbero illeggibili.");
                }
            }
            return r;
        } finally {
            deleteRecursive(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Ricerca dell'NCA Program (preferisce il gioco base all'update)
    // ------------------------------------------------------------------

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
        double v = bytes;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
