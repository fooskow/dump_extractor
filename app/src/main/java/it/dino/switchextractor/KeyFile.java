package it.dino.switchextractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pulisce una prod.keys prima di darla a hactool.
 *
 * hactool, appena incontra una chiave che conosce ma con la lunghezza sbagliata, termina
 * subito il programma. Qui teniamo solo le righe "nome = esadecimale" ben formate e
 * ricordiamo il nome di quelle scartate, cosi' possiamo dire all'utente quale chiave e' rotta.
 *
 * Tutte le chiavi valide di una prod.keys hanno una lunghezza multipla di 16 byte
 * (32 cifre esadecimali): 16 byte, 32 byte, keyblob da 0x90, ecc.
 */
public final class KeyFile {

    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern MASTER_KEY = Pattern.compile("master_key_[0-9a-fA-F]{2}");

    private KeyFile() {
    }

    public static class Report {
        public int kept;
        public boolean hasMasterKey;
        /** Una voce per riga scartata: nome della chiave e motivo. */
        public final List<String> dropped = new ArrayList<String>();
    }

    /** Legge la prod.keys da {@code in} e scrive in {@code out} solo le righe valide. */
    public static Report sanitize(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) {
                throw new IOException("Il file e' troppo grande per essere una prod.keys");
            }
        }
        if (buf.size() == 0) {
            throw new IOException("Il file e' vuoto");
        }

        Charset utf8 = Charset.forName("UTF-8");
        String text = new String(buf.toByteArray(), utf8);
        if (text.length() > 0 && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }

        Report r = new Report();
        StringBuilder sb = new StringBuilder();
        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.trim();
            if (line.length() == 0 || line.charAt(0) == ';' || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (name.length() == 0) {
                continue;
            }
            if (!isHex(value)) {
                r.dropped.add(name + " (il valore non e' esadecimale)");
                continue;
            }
            if (value.length() % 32 != 0) {
                r.dropped.add(name + " (" + value.length() + " cifre esadecimali: non e' una lunghezza valida)");
                continue;
            }
            sb.append(name).append(" = ").append(value.toLowerCase(Locale.ROOT)).append('\n');
            r.kept++;
            if (MASTER_KEY.matcher(name).matches()) {
                r.hasMasterKey = true;
            }
        }
        out.write(sb.toString().getBytes(utf8));
        return r;
    }

    private static boolean isHex(String s) {
        if (s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
