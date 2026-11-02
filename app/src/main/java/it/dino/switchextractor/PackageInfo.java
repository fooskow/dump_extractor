package it.dino.switchextractor;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Quanti byte di NCA ci sono dentro un .xci / .nsp, letti dalle tabelle del contenitore
 * (PFS0 per gli NSP, HFS0 per gli XCI) senza leggere i dati. Serve a sapere in anticipo
 * quanto lavoro c'e' da fare, per la barra di avanzamento.
 */
public final class PackageInfo {

    /** Somma dei file .nca (e' quello che hactool scrive nella prima fase). */
    public final long ncaBytes;
    /** L'NCA piu' grande: di norma il Program, quello da cui si estraggono romfs ed exefs. */
    public final long largestNca;
    public final int ncaCount;

    private PackageInfo(long ncaBytes, long largestNca, int ncaCount) {
        this.ncaBytes = ncaBytes;
        this.largestNca = largestNca;
        this.ncaCount = ncaCount;
    }

    private static final class Entry {
        String name;
        long offset; // relativo alla fine dell'header della tabella
        long size;
    }

    private static final class Table {
        final List<Entry> entries = new ArrayList<Entry>();
        long headerSize; // header + voci + tabella dei nomi
    }

    /** null se il file non e' leggibile o non ha la struttura attesa: il chiamante usa una stima. */
    public static PackageInfo read(File f, boolean xci) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            Table t = xci ? readXciSecure(raf) : readTable(raf, 0, false);
            if (t == null) {
                return null;
            }
            long total = 0;
            long largest = 0;
            int n = 0;
            for (Entry e : t.entries) {
                if (e.name.toLowerCase(Locale.ROOT).endsWith(".nca")) {
                    total += e.size;
                    largest = Math.max(largest, e.size);
                    n++;
                }
            }
            return n == 0 ? null : new PackageInfo(total, largest, n);
        } catch (Exception e) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // niente da fare
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /** XCI: header "HEAD", poi la partizione HFS0 radice, che contiene update/normal/secure/logo. */
    private static Table readXciSecure(RandomAccessFile raf) throws IOException {
        byte[] head = new byte[0x140];
        raf.seek(0);
        raf.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0x100) != 0x44414548) { // "HEAD"
            return null;
        }
        long rootOffset = b.getLong(0x130);
        if (rootOffset <= 0 || rootOffset >= raf.length()) {
            return null;
        }
        Table root = readTable(raf, rootOffset, true);
        if (root == null) {
            return null;
        }
        for (Entry e : root.entries) {
            if ("secure".equals(e.name)) {
                return readTable(raf, rootOffset + root.headerSize + e.offset, true);
            }
        }
        return null;
    }

    /** Legge una tabella PFS0 (voci da 0x18 byte) o HFS0 (voci da 0x40 byte). */
    private static Table readTable(RandomAccessFile raf, long at, boolean hfs0) throws IOException {
        int entrySize = hfs0 ? 0x40 : 0x18;
        byte[] h = new byte[0x10];
        raf.seek(at);
        raf.readFully(h);
        ByteBuffer hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (hb.getInt(0) != (hfs0 ? 0x30534648 : 0x30534650)) { // "HFS0" / "PFS0"
            return null;
        }
        int count = hb.getInt(4);
        int strSize = hb.getInt(8);
        if (count <= 0 || count > 4096 || strSize < 0 || strSize > (1 << 20)) {
            return null;
        }
        byte[] data = new byte[count * entrySize + strSize];
        raf.readFully(data);
        ByteBuffer tb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int strBase = count * entrySize;

        Table t = new Table();
        t.headerSize = 0x10L + data.length;
        for (int i = 0; i < count; i++) {
            int p = i * entrySize;
            Entry e = new Entry();
            e.offset = tb.getLong(p);
            e.size = tb.getLong(p + 8);
            int start = strBase + tb.getInt(p + 16);
            int end = start;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            e.name = start >= data.length ? "" : new String(data, start, end - start, "UTF-8");
            t.entries.add(e);
        }
        return t;
    }
}
