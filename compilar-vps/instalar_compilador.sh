#!/usr/bin/env bash
# 8K PRO — prepara un VPS Ubuntu 24.04 para compilar las APK (se ejecuta UNA vez, como root).
#
#   bash instalar_compilador.sh
#
# Instala Java 21, el SDK de Android (línea de comandos, plataforma 37) y lo necesario para compilar.
# Crea la carpeta /opt/8kpro-compilar con el sitio donde dejar las firmas y los datos privados.
set -euo pipefail

BASE=/opt/8kpro-compilar
SDK=$BASE/android-sdk
CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"

say() { printf '\n\033[1;33m== %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Ejecútalo como root (sudo bash instalar_compilador.sh)"

say "1/5 Comprobando el VPS"
ram_mb=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
swap_mb=$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)
disk_gb=$(df -BG --output=avail / | tail -1 | tr -dc 0-9)
echo "RAM: ${ram_mb} MB · swap: ${swap_mb} MB · disco libre: ${disk_gb} GB · núcleos: $(nproc)"
[ "$disk_gb" -ge 20 ] || die "Hacen falta al menos 20 GB libres de disco."
if [ $((ram_mb + swap_mb)) -lt 12000 ]; then
  # Gradle y Kotlin necesitan unos 10-12 GB entre los dos: con menos RAM, swap para que no se corte.
  need=$(( (12000 - ram_mb - swap_mb) / 1024 + 1 ))
  echo "Poca memoria para compilar: se crea un archivo de intercambio de ${need} GB."
  if [ ! -f /swapfile-8kpro ]; then
    fallocate -l "${need}G" /swapfile-8kpro && chmod 600 /swapfile-8kpro && mkswap /swapfile-8kpro >/dev/null && swapon /swapfile-8kpro
    grep -q swapfile-8kpro /etc/fstab || echo "/swapfile-8kpro none swap sw 0 0" >> /etc/fstab
  fi
fi

say "2/5 Paquetes (Java 21, git, unzip, python3)"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-21-jdk-headless git unzip zip curl python3 ca-certificates >/dev/null

say "3/5 SDK de Android (línea de comandos)"
mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  tmp=$(mktemp -d)
  curl -fsSL "$CMDLINE_URL" -o "$tmp/cmdline.zip"
  unzip -q "$tmp/cmdline.zip" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
export ANDROID_HOME=$SDK
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-37" "build-tools;37.0.0" >/dev/null \
  || "$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-37" >/dev/null

say "4/5 Carpetas de trabajo"
for app in web movil; do
  mkdir -p "$BASE/$app/firma" "$BASE/$app/APK"
  chmod 700 "$BASE/$app/firma"
done
cat > "$BASE/LEEME.txt" <<'TXT'
Copia aquí, desde tu PC, lo mismo que tienes en Documentos (SIN cambiar nada):

  /opt/8kpro-compilar/web/firma/8kpro.keystore     <- de Documentos\8KPROweb\firma
  /opt/8kpro-compilar/web/firma/clave.txt
  /opt/8kpro-compilar/web/server_url.txt           <- de Documentos\8KPROweb
  /opt/8kpro-compilar/web/panel_url.txt
  /opt/8kpro-compilar/web/panel_pin.txt
  /opt/8kpro-compilar/web/tmdb_key.txt

  /opt/8kpro-compilar/movil/firma/8kpro.keystore   <- de Documentos\8KPROmovil\firma
  /opt/8kpro-compilar/movil/firma/clave.txt
  /opt/8kpro-compilar/movil/server_url.txt         <- de Documentos\8KPROmovil
  /opt/8kpro-compilar/movil/panel_url.txt
  /opt/8kpro-compilar/movil/panel_pin.txt
  /opt/8kpro-compilar/movil/tmdb_key.txt

¡Las firmas tienen que ser LAS MISMAS que las del PC! Con otra firma las apps de los clientes
no se podrían actualizar. Nunca subas la carpeta firma a GitHub.
TXT

say "5/5 Script de compilación"
curl -fsSL "https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/compilar-vps/compilar.sh?v=$(date +%s)" -o "$BASE/compilar.sh"
chmod 755 "$BASE/compilar.sh"

cat <<TXT

=====================================================================
  Compilador listo en $BASE

  1. Copia las firmas y los archivos .txt (mira $BASE/LEEME.txt)
     Con MobaXterm: arrastra los archivos a esas carpetas.
  2. Compila:   bash $BASE/compilar.sh todas
     (o: tv | firetv | movil)
  Las APK quedan en $BASE/web/APK y $BASE/movil/APK
=====================================================================
TXT
