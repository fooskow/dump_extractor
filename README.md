# Switch Extractor

App Android (sideload) che estrae le cartelle **romfs** ed **exefs** da un file **.xci** o **.nsp**.
Dentro c'e' hactool compilato per arm64; l'app gli fa fare tutto il giro (NCA, ticket, titlekey,
scelta dell'NCA giusto) e ti lascia le cartelle pronte.

## Come si usa

1. Installa l'APK (abilita "installa app sconosciute" per il browser/file manager).
2. Al primo avvio concedi **"Accesso a tutti i file"** (servono percorsi veri, hactool non
   puo' lavorare su file passati dal selettore di Android).
3. **Scegli prod.keys**: la copia dentro l'app una volta sola, poi non serve piu'.
4. **Scegli il gioco** (.xci o .nsp) e, se vuoi, la cartella di output.
5. **ESTRAI**. Trovi tutto in `<output>/<nome gioco>/romfs` e `.../exefs`.

Note:
- Serve circa il doppio dello spazio del gioco mentre lavora (NCA temporanei + file estratti);
  i temporanei vengono cancellati alla fine.
- Estrae la **versione base**. Se nel pacchetto c'e' anche un update lo ignora.
- Le `prod.keys` devono essere dumpate dalla tua console (Lockpick_RCM) e abbastanza recenti
  per il firmware del gioco.
- Ticket "personalizzati" (legati a una console) non sono supportati.

## Come si costruisce l'APK

Non serve niente sul PC: lo costruisce GitHub Actions.

1. Crea un repository su GitHub e carica **tutto il contenuto di questa cartella**
   (compresa `.github/workflows/`).
2. Vai su **Actions** > **Build APK** > **Run workflow** (parte anche da solo a ogni push).
3. A fine build (qualche minuto) l'APK e' in **Releases > "Switch Extractor (ultima build)"**
   oppure negli *Artifacts* della run.

Se la build fallisce, apri la run, copia l'errore e passalo a Claude.

## Struttura

- `native/build-hactool.sh`: scarica hactool (commit fissato), lo compila con l'NDK e lo
  mette in `app/src/main/jniLibs/arm64-v8a/libhactool.so`.
- `app/.../Extractor.java`: logica di estrazione (senza dipendenze Android).
- `app/.../MainActivity.java`: interfaccia.
- `app/release.keystore`: chiave di firma *usa e getta* inclusa apposta, cosi' le versioni nuove
  si installano sopra le vecchie. Va bene per uso personale; non e' un segreto.
