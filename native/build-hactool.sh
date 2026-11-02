#!/usr/bin/env bash
# Compila hactool per Android arm64 con l'NDK e lo mette dentro l'APK come libhactool.so.
#
# Perche' "libhactool.so"? Android permette di eseguire solo i binari che stanno nella
# cartella delle librerie native dell'app. hactool e' un eseguibile PIE normale: lo
# rinominiamo .so cosi' Gradle lo impacchetta e Android lo estrae (extractNativeLibs=true).
#
# Uso:   ANDROID_NDK_ROOT=/percorso/ndk bash native/build-hactool.sh
set -euo pipefail

# Commit di hactool fissato, cosi' la build e' riproducibile.
HACTOOL_SHA="1d64a83450e025622f3468c28fc4164dad2c5ef6"
API=30

HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-$HERE/app/src/main/jniLibs/arm64-v8a}"
NDK="${ANDROID_NDK_ROOT:?imposta ANDROID_NDK_ROOT}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

CC="$TC/aarch64-linux-android${API}-clang"
AR="$TC/llvm-ar"
RANLIB="$TC/llvm-ranlib"
STRIP="$TC/llvm-strip"

if [ ! -x "$CC" ]; then
  echo "ERRORE: compilatore NDK non trovato: $CC" >&2
  ls "$TC" 2>/dev/null | head -20 >&2 || true
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Scarico hactool @ $HACTOOL_SHA"
git init -q "$WORK/hactool"
cd "$WORK/hactool"
git remote add origin https://github.com/SciresM/hactool.git
git fetch -q --depth 1 origin "$HACTOOL_SHA"
git checkout -q FETCH_HEAD

# bionic (libc di Android) ha FILE opaco: l'aritmetica sui puntatori di hactool (main.c, --basefake)
# non compila. Il valore serve solo come sentinella "non NULL" e viene rimesso a NULL prima di fclose.
grep -q 'nca_ctx.tool_ctx->base_file++;' main.c || { echo "ERRORE: patch bionic non applicabile (main.c cambiato?)" >&2; exit 1; }
sed -i 's/nca_ctx\.tool_ctx->base_file++;/nca_ctx.tool_ctx->base_file = (FILE *)1;/' main.c

# Quando stdout e' una pipe, la libc lo bufferizza a blocchi: le righe "Saving ..." arriverebbero
# a raffiche e la barra di avanzamento scatterebbe. Con il buffer per riga arrivano subito.
grep -q 'hactool_ctx_t base_ctx; /\* Context for base NCA, if used. \*/' main.c || { echo "ERRORE: patch setvbuf non applicabile (main.c cambiato?)" >&2; exit 1; }
sed -i 's|^\(    hactool_ctx_t base_ctx; /\* Context for base NCA, if used. \*/\)$|\1\n    setvbuf(stdout, NULL, _IOLBF, 0);|' main.c
grep -q 'setvbuf(stdout, NULL, _IOLBF, 0);' main.c || { echo "ERRORE: patch setvbuf non applicata" >&2; exit 1; }

# Gli update recenti (es. Zelda TotK 1.4.x) hanno le tabelle BKTR con margini di allineamento:
# hactool le rifiuta con "Invalid BKTR layout!" oppure, peggio, salta il romfs in silenzio
# (controllo total_size). Le tabelle vengono comunque lette dagli offset dichiarati, e oltre la
# fine delle voci reali hactool usa gia' una voce finta che parte dalla tabella di rilocazione:
# basta quindi verificare che tutto stia dentro i limiti. Stampa anche i valori ("[BKTR] ..."),
# cosi' se qualcosa non torna l'app li mostra nel registro; e se la tabella e' davvero
# incoerente esce con errore invece di produrre un romfs vuoto.
python3 - <<'PYEOF'
import sys
src = open("nca.c").read()
old1 = """        if (sb->relocation_header.offset + sb->relocation_header.size != sb->subsection_header.offset ||
            sb->subsection_header.offset + sb->subsection_header.size != ctx->size) {"""
new1 = """        printf("[BKTR] sezione 0x%"PRIx64", rilocazione 0x%"PRIx64"+0x%"PRIx64", sottosezioni 0x%"PRIx64"+0x%"PRIx64"\\n",
               ctx->size, sb->relocation_header.offset, sb->relocation_header.size,
               sb->subsection_header.offset, sb->subsection_header.size);
        if (sb->relocation_header.offset + sb->relocation_header.size > sb->subsection_header.offset ||
            sb->subsection_header.offset + sb->subsection_header.size > ctx->size) {"""
old2 = """        if (ctx->bktr_ctx.subsection_block->total_size != sb->subsection_header.offset) {
            free(relocs);"""
new2 = """        printf("[BKTR] sottosezioni: dimensione coperta 0x%"PRIx64", rilocazione: dimensione virtuale 0x%"PRIx64"\\n",
               ctx->bktr_ctx.subsection_block->total_size, ctx->bktr_ctx.relocation_block->total_size);
        if (ctx->bktr_ctx.subsection_block->total_size > sb->subsection_header.offset) {
            fprintf(stderr, "BKTR incoerente: le sottosezioni coprono 0x%"PRIx64" byte, oltre l'inizio della loro tabella (0x%"PRIx64")\\n",
                    ctx->bktr_ctx.subsection_block->total_size, sb->subsection_header.offset);
            exit(EXIT_FAILURE);
        }
        if (0) {
            free(relocs);"""
for o, n in ((old1, new1), (old2, new2)):
    if src.count(o) != 1:
        sys.exit("ERRORE: patch BKTR non applicabile (nca.c cambiato?)")
    src = src.replace(o, n)
if "inttypes.h" not in src:
    src = "#include <inttypes.h>\n" + src
open("nca.c", "w").write(src)
PYEOF

# Diagnostica per gli update recenti: informazioni di compressione/sparse delle sezioni RomFS,
# intestazione del RomFS virtuale e punto esatto di eventuali letture fallite. Righe "[DIAG] ...".
python3 - <<'PYEOF'
import sys
src = open("nca.c").read()
helper = """
static void diag_fs_header(const char *tag, nca_section_ctx_t *ctx) {
    const uint8_t *h = (const uint8_t *)ctx->header;
    uint64_t c_off, c_size, s_off, s_size; uint32_t c_magic, c_cnt, s_magic, s_cnt;
    memcpy(&s_off, h + 0x148, 8); memcpy(&s_size, h + 0x150, 8); memcpy(&s_magic, h + 0x158, 4); memcpy(&s_cnt, h + 0x160, 4);
    memcpy(&c_off, h + 0x178, 8); memcpy(&c_size, h + 0x180, 8); memcpy(&c_magic, h + 0x188, 4); memcpy(&c_cnt, h + 0x190, 4);
    printf("[DIAG] %s: sezione 0x%"PRIx64", cifratura %u, compressione 0x%"PRIx64"+0x%"PRIx64" (magic %08x, voci %u), sparse 0x%"PRIx64"+0x%"PRIx64" (magic %08x, voci %u)\\n",
           tag, ctx->size, (unsigned)ctx->header->crypt_type, c_off, c_size, c_magic, c_cnt, s_off, s_size, s_magic, s_cnt);
}

"""
a1 = "void nca_process_ivfc_section(nca_section_ctx_t *ctx) {"
a2 = "void nca_process_bktr_section(nca_section_ctx_t *ctx) {"
o3 = """            if (nca_section_fread(ctx, &ctx->bktr_ctx.header, sizeof(romfs_hdr_t)) != sizeof(romfs_hdr_t)) {
                fprintf(stderr, "Failed to read BKTR Virtual RomFS header!\\n");
            }
"""
n3 = o3 + """            printf("[DIAG] RomFS virtuale a 0x%"PRIx64": header 0x%"PRIx64", cartelle 0x%"PRIx64"+0x%"PRIx64", file 0x%"PRIx64"+0x%"PRIx64", dati 0x%"PRIx64"\\n",
                   ctx->bktr_ctx.romfs_offset, ctx->bktr_ctx.header.header_size,
                   ctx->bktr_ctx.header.dir_meta_table_offset, ctx->bktr_ctx.header.dir_meta_table_size,
                   ctx->bktr_ctx.header.file_meta_table_offset, ctx->bktr_ctx.header.file_meta_table_size,
                   ctx->bktr_ctx.header.data_offset);
"""
o4 = """    bktr_subsection_entry_t *subsec = bktr_get_subsection(ctx->bktr_ctx.subsection_block, ctx->bktr_ctx.bktr_seek);"""
n4 = """    {
        struct stat diag_st;
        if (fstat(fileno(ctx->file), &diag_st) == 0 && (uint64_t)ctx->offset + ctx->bktr_ctx.bktr_seek + count > (uint64_t)diag_st.st_size) {
            fprintf(stderr, "[DIAG] lettura oltre la fine dell'NCA: virtuale 0x%"PRIx64" -> fisico 0x%"PRIx64" (+0x%zx), sezione da 0x%"PRIx64", file 0x%"PRIx64"\\n",
                    ctx->bktr_ctx.virtual_seek, ctx->bktr_ctx.bktr_seek, count, (uint64_t)ctx->offset, (uint64_t)diag_st.st_size);
        }
    }
""" + o4
for o in (a1, a2, o3, o4):
    if src.count(o) != 1:
        sys.exit("ERRORE: patch diagnostica non applicabile: " + o[:40])
src = src.replace(a1, helper + a1 + '\n    diag_fs_header("RomFS", ctx);', 1)
src = src.replace(a2, a2 + '\n    diag_fs_header("BKTR", ctx);', 1)
src = src.replace(o3, n3, 1)
src = src.replace(o4, n4, 1)
if "#include <sys/stat.h>" not in src:
    src = "#include <sys/stat.h>\n" + src
if "#include <string.h>" not in src:
    src = "#include <string.h>\n" + src
open("nca.c", "w").write(src)
PYEOF

# hactool termina subito (exit) se una chiave che conosce ha la lunghezza sbagliata: nelle prod.keys
# reali capita (es. una riga con un byte in piu'). Per le chiavi lette da file la ignoriamo (resta a
# zero) invece di chiudere il programma; da riga di comando (--titlekey) il comportamento non cambia.
python3 - <<'PYEOF'
import re, sys
src = open("extkeys.c").read()
m = re.search(r"void parse_hex_key\(unsigned char \*key, const char \*hex, unsigned int len\) \{.*?\n\}\n", src, re.S)
if not m or "parse_hex_key_lenient" in src:
    sys.exit("ERRORE: patch 'lenient' non applicabile (extkeys.c cambiato?)")
add = """
static void parse_hex_key_lenient(unsigned char *key, const char *hex, unsigned int len) {
    size_t n = strlen(hex);
    int ok = (n == 2 * (size_t)len);
    for (size_t i = 0; ok && i < n; i++) {
        if (!ishex(hex[i])) {
            ok = 0;
        }
    }
    if (!ok) {
        fprintf(stderr, "[WARN]: chiave ignorata, servono %u cifre esadecimali (trovate %u)\\n", 2 * len, (unsigned int)n);
        memset(key, 0, len);
        return;
    }
    parse_hex_key(key, hex, len);
}
#define parse_hex_key parse_hex_key_lenient
"""
open("extkeys.c", "w").write(src[:m.end()] + add + src[m.end():])
PYEOF

cat > config.mk <<EOF
CC = $CC
AR = $AR
RANLIB = $RANLIB
CFLAGS = -O2 -fPIE -std=gnu11 -w
LDFLAGS = -pie -Wl,-z,max-page-size=16384 -lmbedtls -lmbedx509 -lmbedcrypto
EOF

echo "==> Compilo (mbedtls + hactool)"
make -j"$(nproc)" CC="$CC" AR="$AR" RANLIB="$RANLIB"

[ -f hactool ] || { echo "ERRORE: hactool non e' stato generato" >&2; exit 1; }
"$STRIP" hactool 2>/dev/null || true

if [ "${SKIP_ARCH_CHECK:-0}" != "1" ]; then
  if command -v file >/dev/null 2>&1; then
    file hactool | tee /dev/stderr | grep -q 'ARM aarch64' \
      || { echo "ERRORE: il binario non e' arm64" >&2; exit 1; }
  fi
fi

mkdir -p "$OUT_DIR"
cp hactool "$OUT_DIR/libhactool.so"
chmod 755 "$OUT_DIR/libhactool.so"
echo "==> OK: $OUT_DIR/libhactool.so ($(du -h "$OUT_DIR/libhactool.so" | cut -f1))"
