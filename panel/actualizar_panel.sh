#!/usr/bin/env bash
# 8K PRO — actualiza el panel a la última versión (v1.3+: estadísticas "En directo" y mapa de clientes).
#
# Uso (en el VPS, como root o con sudo):
#   curl -fsSL "https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel/actualizar_panel.sh?v=$(date +%s)" | sudo bash
#
# Qué hace (se puede ejecutar las veces que quieras; conserva administrador, dispositivos, DNS y certificado):
#   1. Instala python3-maxminddb (lector de la base de datos de geolocalización).
#   2. Descarga la última versión de panel.py.
#   3. Descarga Leaflet (la librería del mapa) para servirla desde el propio panel.
#   4. Descarga la base de datos de geolocalización por IP "DB-IP City Lite" (gratuita, CC BY 4.0)
#      y deja programada su actualización automática cada mes.
#   5. Reinicia el panel y comprueba que responde.
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel"
APP_DIR=/opt/8kpro-panel
DATA_DIR=/var/lib/8kpro-panel
GEO_DIR=$DATA_DIR/geo
SVC_USER=8kpro-panel
LEAFLET_VERSION=1.9.4

say()  { printf '\n\033[1;33m== %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mAVISO: %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Ejecútalo como root (con sudo)."
[ -d "$APP_DIR" ] && id "$SVC_USER" >/dev/null 2>&1 || die "No encuentro el panel instalado. Instálalo primero con instalar_panel.sh."

say "1/5 Panel"
# Lo primero: aunque luego falle algo opcional, el panel ya queda actualizado.
curl -fsSL -H 'Cache-Control: no-cache' "$REPO_RAW/app/panel.py?v=$(date +%s)" -o "$APP_DIR/panel.py.new" </dev/null \
  || die "No se pudo descargar panel.py desde GitHub (¿hay internet en el VPS?)"
python3 -m py_compile "$APP_DIR/panel.py.new" || die "panel.py descargado no es válido"
mv "$APP_DIR/panel.py.new" "$APP_DIR/panel.py"
chmod 755 "$APP_DIR/panel.py"
rm -rf "$APP_DIR/__pycache__"
grep -m1 '^PANEL_VERSION' "$APP_DIR/panel.py"
systemctl restart 8kpro-panel || warn "No se pudo reiniciar el panel"

say "2/5 Paquetes"
export DEBIAN_FRONTEND=noninteractive
# Un repositorio roto en el VPS no debe parar la actualización: estos pasos son opcionales.
apt-get update -qq </dev/null || warn "apt-get update dio errores; se intenta instalar igualmente."
if ! apt-get install -y -qq python3-maxminddb curl ca-certificates gzip >/dev/null </dev/null; then
  warn "No se pudo instalar python3-maxminddb: el panel funcionará, pero sin mapa ni ubicaciones."
fi

say "3/5 Leaflet $LEAFLET_VERSION (mapa)"
install -d -m 755 "$APP_DIR/static/leaflet"
for f in leaflet.js leaflet.css; do
  ok=0
  for base in "https://cdn.jsdelivr.net/npm/leaflet@$LEAFLET_VERSION/dist" "https://unpkg.com/leaflet@$LEAFLET_VERSION/dist"; do
    if curl -fsSL --max-time 60 "$base/$f" -o "$APP_DIR/static/leaflet/$f.new" && [ -s "$APP_DIR/static/leaflet/$f.new" ]; then
      mv "$APP_DIR/static/leaflet/$f.new" "$APP_DIR/static/leaflet/$f"; ok=1; break
    fi
  done
  [ $ok -eq 1 ] || warn "No se pudo descargar $f: el mapa no se mostrará hasta que vuelvas a ejecutar este script."
done
chmod -R a+rX "$APP_DIR/static"

say "4/5 Geolocalización por IP (DB-IP City Lite)"
install -d -m 750 -o "$SVC_USER" -g "$SVC_USER" "$GEO_DIR"
cat > /usr/local/sbin/8kpro-geo-update <<'EOF'
#!/usr/bin/env bash
# Descarga la base de datos "DB-IP City Lite" (CC BY 4.0, https://db-ip.com) y la cambia sin cortar el panel.
set -uo pipefail
GEO_DIR=/var/lib/8kpro-panel/geo
DEST=$GEO_DIR/dbip-city-lite.mmdb
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
urls=(
  "https://download.db-ip.com/free/dbip-city-lite-$(date +%Y-%m).mmdb.gz"
  "https://download.db-ip.com/free/dbip-city-lite-$(date -d "$(date +%Y-%m-01) -1 month" +%Y-%m).mmdb.gz"
  "https://cdn.jsdelivr.net/npm/dbip-city-lite/dbip-city-lite.mmdb.gz"
)
for u in "${urls[@]}"; do
  if curl -fsSL --max-time 600 "$u" -o "$TMP/db.mmdb.gz" && gunzip -f "$TMP/db.mmdb.gz" 2>/dev/null \
     && python3 -c "import maxminddb,sys; r=maxminddb.open_database(sys.argv[1]); r.get('8.8.8.8'); r.close()" "$TMP/db.mmdb"; then
    install -m 640 -o 8kpro-panel -g 8kpro-panel "$TMP/db.mmdb" "$DEST.new" && mv -f "$DEST.new" "$DEST"
    echo "Geolocalización actualizada desde $u"
    exit 0
  fi
done
echo "No se pudo descargar la base de datos de geolocalización (se conserva la anterior si existe)." >&2
exit 1
EOF
chmod 755 /usr/local/sbin/8kpro-geo-update
if ! /usr/local/sbin/8kpro-geo-update </dev/null; then
  warn "Sin base de datos de geolocalización por ahora; se volverá a intentar automáticamente."
fi
cat > /etc/systemd/system/8kpro-geo-update.service <<'EOF'
[Unit]
Description=8K PRO: actualiza la base de datos de geolocalización por IP
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/8kpro-geo-update
EOF
cat > /etc/systemd/system/8kpro-geo-update.timer <<'EOF'
[Unit]
Description=8K PRO: geolocalización por IP, una vez al mes

[Timer]
OnCalendar=*-*-05 04:30:00
RandomizedDelaySec=2h
Persistent=true

[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now 8kpro-geo-update.timer >/dev/null

say "5/5 Reiniciando el panel"
systemctl restart 8kpro-panel
sleep 2
if curl -fsSk --max-time 5 "https://127.0.0.1/api/v1/ping" | grep -q '"ok"'; then OK="funcionando"; else OK="NO responde (mira: journalctl -u 8kpro-panel -n 50)"; fi
GEO_OK="no"; [ -s "$GEO_DIR/dbip-city-lite.mmdb" ] && GEO_OK="sí"
MAP_OK="no"; [ -s "$APP_DIR/static/leaflet/leaflet.js" ] && MAP_OK="sí"

cat <<EOF

=====================================================================
  Panel 8K PRO: $OK  ($(grep -m1 '^PANEL_VERSION' "$APP_DIR/panel.py" | cut -d'"' -f2))
  Geolocalización por IP: $GEO_OK     Mapa (Leaflet): $MAP_OK

  Nuevas secciones en el panel:  "En directo"  y  "Mapa"
  Los dispositivos empiezan a enviar estadísticas cuando tengan
  instalada la nueva versión de la app 8KPROweb.
=====================================================================
EOF
