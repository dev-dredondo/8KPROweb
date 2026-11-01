#!/usr/bin/env bash
# 8K PRO — compila las APK en el VPS (después de instalar_compilador.sh).
#
#   bash /opt/8kpro-compilar/compilar.sh todas      # TV, Fire TV y móvil
#   bash /opt/8kpro-compilar/compilar.sh tv         # solo 8KPROweb
#   bash /opt/8kpro-compilar/compilar.sh firetv     # solo 8KPROweb para Fire TV antiguos
#   bash /opt/8kpro-compilar/compilar.sh movil      # solo 8KPROmovil
#
# Descarga siempre la última versión del código de GitHub. Las APK quedan en
# /opt/8kpro-compilar/web/APK y /opt/8kpro-compilar/movil/APK, y el registro en compilacion-*.log.
set -uo pipefail

BASE=/opt/8kpro-compilar
export ANDROID_HOME=$BASE/android-sdk
export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
QUE=${1:-todas}

say()  { printf '\n\033[1;33m== %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m%s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mERROR: %s\033[0m\n' "$*"; }

# Clona o actualiza el repositorio $1 en $2.
codigo() {
  local repo=$1 dir=$2
  if [ -d "$dir/.git" ]; then
    git -C "$dir" fetch -q origin main && git -C "$dir" reset -q --hard origin/main && git -C "$dir" clean -qfdx -e local.properties
  else
    rm -rf "$dir"; git clone -q --depth 1 "https://github.com/dev-dredondo/$repo.git" "$dir"
  fi
  echo "sdk.dir=$ANDROID_HOME" > "$dir/local.properties"
}

# Lee los archivos privados y la firma de la carpeta $1 (web o movil) y deja los argumentos en ARGS.
datos() {
  local d=$BASE/$1
  for f in server_url.txt firma/8kpro.keystore firma/clave.txt; do
    [ -s "$d/$f" ] || { fail "falta $d/$f (mira $BASE/LEEME.txt)"; return 1; }
  done
  ARGS=("-Pbrand.serverUrl=$(tr -d '\r\n' < "$d/server_url.txt")")
  [ -s "$d/tmdb_key.txt" ] && ARGS+=("-Pbrand.tmdbApiKey=$(tr -d '\r\n' < "$d/tmdb_key.txt")")
  if [ -s "$d/panel_url.txt" ] && [ -s "$d/panel_pin.txt" ]; then
    ARGS+=("-Pbrand.panelUrl=$(tr -d '\r\n' < "$d/panel_url.txt")" "-Pbrand.panelPin=$(tr -d '\r\n' < "$d/panel_pin.txt")")
  fi
  local pass; pass=$(tr -d '\r\n' < "$d/firma/clave.txt")
  export KEYSTORE_FILE=$d/firma/8kpro.keystore KEYSTORE_PASSWORD=$pass KEY_ALIAS=8kpro KEY_PASSWORD=$pass
}

# Compila en $1 con los argumentos extra $2..; si falla, una vez más sin la caché de Gradle.
gradle() {
  local dir=$1; shift
  ( cd "$dir" && ./gradlew --console=plain :app:assembleStandardRelease "$@" "${ARGS[@]}" ) >> "$LOG" 2>&1 && return 0
  echo "Fallo; se repite sin la caché de Gradle" | tee -a "$LOG"
  ( cd "$dir" && ./gradlew --console=plain --no-build-cache :app:assembleStandardRelease "$@" "${ARGS[@]}" ) >> "$LOG" 2>&1
}

version() { tr -d '\r\n' < "$1/version.txt"; }

tv() {
  say "8KPROweb (TV)"; LOG=$BASE/compilacion-tv.log; : > "$LOG"
  codigo 8KPROweb "$BASE/web/codigo" && datos web || return 1
  chmod +x "$BASE/web/codigo/gradlew"
  if gradle "$BASE/web/codigo" "-Pbrand.abis=armeabi-v7a,arm64-v8a,x86_64"; then
    cp "$BASE/web/codigo/app/build/outputs/apk/standard/release/app-standard-release.apk" "$BASE/web/APK/8KPROweb.apk"
    ok "LISTO: $BASE/web/APK/8KPROweb.apk ($(version "$BASE/web/codigo"))"
  else fail "8KPROweb no compiló, mira $LOG"; return 1; fi
}

firetv() {
  say "8KPROweb para Fire TV antiguos"; LOG=$BASE/compilacion-firetv.log; : > "$LOG"
  codigo 8KPROweb "$BASE/web/codigo-firetv" && datos web || return 1
  chmod +x "$BASE/web/codigo-firetv/gradlew"
  if gradle "$BASE/web/codigo-firetv" -Pbrand.firetv=true "-Pbrand.abis=armeabi-v7a" \
      "-Pbrand.updateUrl=https://raw.githubusercontent.com/dev-dredondo/8KPROweb/apk/version-firetv.json"; then
    cp "$BASE/web/codigo-firetv/app/build/outputs/apk/standard/release/app-standard-release.apk" "$BASE/web/APK/8KPROweb_FireTV.apk"
    ok "LISTO: $BASE/web/APK/8KPROweb_FireTV.apk ($(version "$BASE/web/codigo-firetv"))"
  else fail "Fire TV no compiló, mira $LOG"; return 1; fi
}

movil() {
  say "8KPROmovil"; LOG=$BASE/compilacion-movil.log; : > "$LOG"
  codigo 8KPROmovil "$BASE/movil/codigo" && datos movil || return 1
  chmod +x "$BASE/movil/codigo/gradlew"
  if gradle "$BASE/movil/codigo" "-Pbrand.abis=armeabi-v7a,arm64-v8a,x86_64"; then
    cp "$BASE/movil/codigo/app/build/outputs/apk/standard/release/app-standard-release.apk" "$BASE/movil/APK/8KPROmovil.apk"
    ok "LISTO: $BASE/movil/APK/8KPROmovil.apk ($(version "$BASE/movil/codigo"))"
  else fail "8KPROmovil no compiló, mira $LOG"; return 1; fi
}

[ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ] || { fail "Primero ejecuta instalar_compilador.sh"; exit 1; }
rc=0
case "$QUE" in
  tv) tv || rc=1 ;;
  firetv) firetv || rc=1 ;;
  movil) movil || rc=1 ;;
  todas) tv || rc=1; firetv || rc=1; movil || rc=1 ;;
  *) echo "Uso: bash compilar.sh todas|tv|firetv|movil"; exit 2 ;;
esac
exit $rc
