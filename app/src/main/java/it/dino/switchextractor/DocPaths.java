package it.dino.switchextractor;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;

/** Da un URI del selettore di sistema al percorso vero del file, quando e' ricavabile. */
final class DocPaths {

    private DocPaths() {
    }

    /**
     * Converte un document id del provider della memoria ("primary:Download/x.xci",
     * "1234-ABCD:Games") in un percorso vero. null se non e' il provider della memoria.
     */
    static String pathFromDocId(String docId, String authority) {
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

    /** Percorso reale del file scelto, se ricavabile e leggibile; altrimenti null. */
    static File resolve(Context ctx, Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
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
}
