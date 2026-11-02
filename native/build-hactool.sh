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
