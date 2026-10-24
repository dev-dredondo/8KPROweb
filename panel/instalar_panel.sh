#!/usr/bin/env bash
# 8K PRO — instalador del panel de activación para Ubuntu 24.04 (servidor limpio).
#
# Uso (como root o con sudo):
#   curl -fsSL https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel/instalar_panel.sh \
#     | sudo DNS=http://tu-servidor-xtream bash
#
# Variables opcionales: DNS (DNS Xtream por defecto), PANEL_IP (IP pública; se detecta sola).
# Se puede volver a ejecutar para actualizar: conserva usuarios, dispositivos, certificado y ajustes.
set -euo pipefail

REPO_RAW="https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel"
APP_DIR=/opt/8kpro-panel
CONF_DIR=/etc/8kpro-panel
DATA_DIR=/var/lib/8kpro-panel
TLS_DIR=$CONF_DIR/tls
SVC_USER=8kpro-panel

say()  { printf '\n\033[1;33m== %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
ask()  { local __v; read -r -p "$1" __v </dev/tty; printf '%s' "$__v"; }

[ "$(id -u)" -eq 0 ] || die "Ejecútalo como root (con sudo)."
. /etc/os-release
[ "${ID:-}" = "ubuntu" ] || die "Este instalador es para Ubuntu (detectado: ${ID:-desconocido})."

say "1/7 Paquetes del sistema"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq nginx openssl ufw python3 curl ca-certificates >/dev/null

say "2/7 Usuario y carpetas"
id "$SVC_USER" >/dev/null 2>&1 || useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin "$SVC_USER"
install -d -m 755 "$APP_DIR"
install -d -m 750 -o root -g "$SVC_USER" "$CONF_DIR"
install -d -m 700 -o "$SVC_USER" -g "$SVC_USER" "$DATA_DIR"
install -d -m 700 "$TLS_DIR"

say "3/7 Descargando el panel"
# "?v=…" salta la caché de GitHub (hasta 5 min): siempre la última versión publicada.
curl -fsSL -H 'Cache-Control: no-cache' "$REPO_RAW/app/panel.py?v=$(date +%s)" -o "$APP_DIR/panel.py.new"
python3 -m py_compile "$APP_DIR/panel.py.new" || die "panel.py descargado no es válido"
mv "$APP_DIR/panel.py.new" "$APP_DIR/panel.py"
chmod 755 "$APP_DIR/panel.py"

say "4/7 Configuración"
if [ ! -f "$CONF_DIR/config.json" ]; then
  ADMIN_USER=$(ask "Usuario administrador del panel [admin]: ")
  ADMIN_USER=${ADMIN_USER:-admin}
  while :; do
    read -r -s -p "Contraseña del administrador (mín. 10 caracteres): " P1 </dev/tty; echo
    read -r -s -p "Repite la contraseña: " P2 </dev/tty; echo
    [ "$P1" = "$P2" ] || { echo "No coinciden, otra vez."; continue; }
    [ "${#P1}" -ge 10 ] || { echo "Demasiado corta, otra vez."; continue; }
    break
  done
  HASH=$(printf '%s\n' "$P1" | python3 "$APP_DIR/panel.py" hash)
  unset P1 P2
  SECRET=$(openssl rand -hex 32)
  python3 - "$ADMIN_USER" "$HASH" "$SECRET" > "$CONF_DIR/config.json" <<'PY'
import json, sys
print(json.dumps({"admin_user": sys.argv[1], "admin_hash": sys.argv[2], "secret": sys.argv[3]}, indent=2))
PY
  chown root:"$SVC_USER" "$CONF_DIR/config.json"; chmod 640 "$CONF_DIR/config.json"
  echo "Administrador '$ADMIN_USER' creado."
else
  echo "Configuración existente conservada ($CONF_DIR/config.json)."
fi

# DNS global por defecto: de la variable DNS, o se pregunta si aún no hay ninguna.
CURRENT_DNS=$(PANEL_DB="$DATA_DIR/panel.db" python3 - <<'PY'
import os, sqlite3
p = os.environ["PANEL_DB"]
try:
    c = sqlite3.connect(f"file:{p}?mode=ro", uri=True); r = c.execute("SELECT value FROM settings WHERE key='default_server_url'").fetchone()
    print(r[0] if r else "")
except Exception:
    print("")
PY
)
if [ -n "${DNS:-}" ]; then
  sudo -u "$SVC_USER" PANEL_DB="$DATA_DIR/panel.db" PANEL_CONFIG="$CONF_DIR/config.json" python3 "$APP_DIR/panel.py" set-dns "$DNS"
elif [ -z "$CURRENT_DNS" ]; then
  D=$(ask "DNS del servidor Xtream por defecto (http://host:puerto): ")
  [ -n "$D" ] && sudo -u "$SVC_USER" PANEL_DB="$DATA_DIR/panel.db" PANEL_CONFIG="$CONF_DIR/config.json" python3 "$APP_DIR/panel.py" set-dns "$D"
else
  echo "DNS global conservada: $CURRENT_DNS"
fi

say "5/7 Certificado HTTPS"
if [ -z "${PANEL_IP:-}" ]; then
  PANEL_IP=$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null || true)
  [ -n "$PANEL_IP" ] || PANEL_IP=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
fi
[ -n "$PANEL_IP" ] || die "No se pudo detectar la IP pública. Vuelve a ejecutar con PANEL_IP=x.x.x.x"
if [ ! -f "$TLS_DIR/panel.crt" ] || ! openssl x509 -in "$TLS_DIR/panel.crt" -noout -text | grep -q "IP Address:$PANEL_IP"; then
  openssl req -x509 -nodes -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -days 3650 \
    -subj "/CN=$PANEL_IP/O=8K PRO" -addext "subjectAltName=IP:$PANEL_IP" \
    -keyout "$TLS_DIR/panel.key" -out "$TLS_DIR/panel.crt" 2>/dev/null
  chmod 600 "$TLS_DIR/panel.key"
  echo "Certificado nuevo para $PANEL_IP (válido 10 años)."
else
  echo "Certificado existente conservado."
fi
PIN=$(openssl x509 -in "$TLS_DIR/panel.crt" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64)

say "6/7 Servicio y Nginx"
cat > /etc/systemd/system/8kpro-panel.service <<EOF
[Unit]
Description=8K PRO panel de activación
After=network.target

[Service]
User=$SVC_USER
Group=$SVC_USER
Environment=PANEL_CONFIG=$CONF_DIR/config.json PANEL_DB=$DATA_DIR/panel.db PANEL_HOST=127.0.0.1 PANEL_PORT=8080
ExecStart=/usr/bin/python3 $APP_DIR/panel.py
Restart=always
RestartSec=3
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true
ReadWritePaths=$DATA_DIR

[Install]
WantedBy=multi-user.target
EOF
chown -R "$SVC_USER":"$SVC_USER" "$DATA_DIR"

cat > /etc/nginx/sites-available/8kpro-panel <<EOF
limit_req_zone \$binary_remote_addr zone=panel_api:10m rate=30r/m;
limit_req_zone \$binary_remote_addr zone=panel_login:10m rate=10r/m;

server {
    listen 80 default_server;
    server_name _;
    return 301 https://\$host\$request_uri;
}

server {
    listen 443 ssl default_server;
    server_name _;
    ssl_certificate     $TLS_DIR/panel.crt;
    ssl_certificate_key $TLS_DIR/panel.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    server_tokens off;
    client_max_body_size 16k;
    add_header Strict-Transport-Security "max-age=31536000" always;

    location = /api/v1/register { limit_req zone=panel_api burst=10 nodelay; include /etc/nginx/8kpro-proxy.conf; }
    location /api/             { limit_req zone=panel_api burst=60 nodelay; include /etc/nginx/8kpro-proxy.conf; }
    location = /admin/login    { limit_req zone=panel_login burst=5 nodelay; include /etc/nginx/8kpro-proxy.conf; }
    location /                 { include /etc/nginx/8kpro-proxy.conf; }
}
EOF
cat > /etc/nginx/8kpro-proxy.conf <<'EOF'
proxy_pass http://127.0.0.1:8080;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_read_timeout 30s;
EOF
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/8kpro-panel /etc/nginx/sites-enabled/8kpro-panel
nginx -t
systemctl daemon-reload
systemctl enable --now 8kpro-panel >/dev/null
systemctl restart 8kpro-panel
systemctl enable nginx >/dev/null
systemctl reload nginx || systemctl restart nginx

say "7/7 Cortafuegos"
# SSH primero, en el puerto que use realmente (no solo el 22), para no quedarte fuera.
# (Ubuntu 24.04 arranca SSH por socket de systemd, así que se mira también la configuración de sshd.)
SSH_PORTS=$( { sshd -T 2>/dev/null | awk '/^port /{print $2}'; ss -tlnpH 2>/dev/null | awk '/sshd/ {n=split($4,a,":"); print a[n]}'; } | sort -u)
[ -n "$SSH_PORTS" ] || SSH_PORTS=22
for p in $SSH_PORTS; do ufw allow "$p"/tcp >/dev/null; echo "SSH permitido en el puerto $p"; done
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null
ufw status | sed -n '1,10p'

sleep 1
if curl -fsSk --max-time 5 "https://127.0.0.1/api/v1/ping" | grep -q '"ok"'; then OK="funcionando"; else OK="NO responde (mira: journalctl -u 8kpro-panel -n 50)"; fi

cat <<EOF

=====================================================================
  Panel 8K PRO: $OK

  Abre en el navegador:   https://$PANEL_IP/admin
  (la primera vez el navegador avisará del certificado: "Avanzado" -> "Continuar")

  Para compilar la app, pasa estas DOS líneas a Claude:
    PANEL_URL=https://$PANEL_IP
    PANEL_PIN=$PIN
=====================================================================
EOF
