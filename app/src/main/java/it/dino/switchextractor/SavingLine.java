package it.dino.switchextractor;

/** Interpreta le righe "Saving ..." che hactool stampa per ogni file che scrive. */
public final class SavingLine {

    private SavingLine() {
    }

    /**
     * romfs:  "Saving /percorso/assoluto/file..."
     * exefs:  "Saving main to /percorso/assoluto/main..."
     * Restituisce il percorso del file che sta per essere scritto, o null se non e' una riga di salvataggio.
     */
    public static String path(String line) {
        if (line == null || !line.startsWith("Saving ")) {
            return null;
        }
        String rest = line.substring(7);
        if (rest.endsWith("...")) {
            rest = rest.substring(0, rest.length() - 3);
        }
        if (rest.startsWith("/")) {
            return rest;
        }
        int i = rest.indexOf(" to /");
        return i >= 0 ? rest.substring(i + 4) : null;
    }
}
