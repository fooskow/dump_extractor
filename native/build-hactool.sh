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
