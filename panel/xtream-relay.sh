#!/usr/bin/env bash
# =============================================================================
#  xtream-relay.sh — Usa tu VPS como punto de entrada hacia un servidor Xtream
#  Codes de un proveedor, de forma que el PROVEEDOR VEA LA IP DEL USUARIO FINAL.
#
#  Cómo lo consigue: el VPS no retransmite el tráfico, sino que responde con
#  redirecciones HTTP 302 hacia el proveedor. El reproductor sigue la
#  redirección y se conecta directamente al proveedor desde su propia IP.
#
#  Modos:
#    redirect (por defecto) -> TODO se redirige (API + streams + EPG).
#                              El proveedor ve la IP del usuario en el 100%
#                              de las peticiones.
#    hybrid                 -> La API (player_api.php, get.php, xmltv.php,
#                              panel_api.php) pasa por el VPS con cabeceras
#                              X-Real-IP / X-Forwarded-For; los streams se
#                              redirigen. OJO: en la API el proveedor ve la
#                              IP del VPS salvo que confíe en esas cabeceras.
#
#  Requisitos: Debian/Ubuntu, ejecutar como root.
#
#  Convive con el panel 8KPROweb (panel/instalar_panel.sh): si detecta el
#  panel instalado en este VPS, el relay escucha en el puerto 8880 (el panel
#  ocupa 80/443 y 8080 internamente) y no usa HTTPS propio.
#
#  Uso:
#    PROVIDER_URL="http://proveedor.com:8080" bash xtream-relay.sh
#    PROVIDER_URL="http://proveedor.com:8080" DOMAIN="tv.midominio.com" \
#      EMAIL="yo@midominio.com" bash xtream-relay.sh          # con HTTPS
#    PROVIDER_URL="..." MODE=hybrid bash xtream-relay.sh
#    PROVIDER_URL="..." LISTEN_PORT=8880 bash xtream-relay.sh  # puerto concreto
# =============================================================================
set -euo pipefail

# ----------------------------- CONFIGURACIÓN ---------------------------------
PROVIDER_URL="${PROVIDER_URL:-http://proveedor.ejemplo.com:8080}"  # sin ruta final
MODE="${MODE:-redirect}"          # redirect | hybrid
DOMAIN="${DOMAIN:-}"              # opcional: dominio que apunta al VPS (para HTTPS)
EMAIL="${EMAIL:-}"                # obligatorio si usas DOMAIN (Let's Encrypt)
LISTEN_PORT="${LISTEN_PORT:-}"    # puerto HTTP en el VPS (vacío = automático)
REDIRECT_CODE="${REDIRECT_CODE:-302}"  # 302 (estándar) o 307 (conserva POST)
# -----------------------------------------------------------------------------

log()  { echo -e "\e[32m[+]\e[0m $*"; }
warn() { echo -e "\e[33m[!]\e[0m $*"; }
die()  { echo -e "\e[31m[x]\e[0m $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "Ejecuta el script como root (sudo)."
[[ "$MODE" == "redirect" || "$MODE" == "hybrid" ]] || die "MODE debe ser 'redirect' o 'hybrid'."
[[ "$PROVIDER_URL" =~ ^https?://[^/]+/?$ ]] || die "PROVIDER_URL debe ser tipo http://host:puerto (sin ruta)."
[[ -n "$DOMAIN" && -z "$EMAIL" ]] && die "Si defines DOMAIN, define también EMAIL."

# ¿Está instalado el panel 8KPROweb en este VPS?
PANEL_PRESENT=0
[[ -e /etc/nginx/sites-enabled/8kpro-panel ]] && PANEL_PRESENT=1

if [[ -z "$LISTEN_PORT" ]]; then
    [[ $PANEL_PRESENT -eq 1 ]] && LISTEN_PORT=8880 || LISTEN_PORT=80
fi
[[ "$LISTEN_PORT" =~ ^[0-9]+$ ]] || die "LISTEN_PORT debe ser un número."

DEFAULT_SERVER="default_server"
if [[ $PANEL_PRESENT -eq 1 ]]; then
    warn "Panel 8KPROweb detectado: el relay usará el puerto ${LISTEN_PORT}."
    [[ "$LISTEN_PORT" =~ ^(80|443|8080)$ ]] && die "El puerto ${LISTEN_PORT} lo usa el panel. Elige otro LISTEN_PORT (p. ej. 8880)."
    DEFAULT_SERVER=""
    if [[ -n "$DOMAIN" ]]; then
        warn "Con el panel instalado no se configura HTTPS para el relay; se ignora DOMAIN."
        DOMAIN=""
    fi
fi

PROVIDER_URL="${PROVIDER_URL%/}"
PROVIDER_HOSTPORT="${PROVIDER_URL#*://}"      # host:puerto
SERVER_NAME="${DOMAIN:-_}"
CONF="/etc/nginx/sites-available/xtream-relay.conf"

log "Proveedor:  $PROVIDER_URL"
log "Modo:       $MODE"
log "Escucha en: puerto $LISTEN_PORT ${DOMAIN:+(dominio $DOMAIN)}"

# ----------------------------- INSTALACIÓN -----------------------------------
log "Instalando nginx y curl..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y >/dev/null
apt-get install -y nginx curl >/dev/null

# ------------------------- BLOQUES DE CONFIGURACIÓN --------------------------
REDIRECT_BLOCK="
    # Todo lo demás (streams live/movie/series/timeshift, HLS, EPG...) -> 302
    location / {
        add_header Cache-Control \"no-store\" always;
        return ${REDIRECT_CODE} ${PROVIDER_URL}\$request_uri;
    }"

HYBRID_BLOCK=""
if [[ "$MODE" == "hybrid" ]]; then
HYBRID_BLOCK="
    # API Xtream pasa por el VPS reenviando la IP del cliente en cabeceras
    location ~ ^/(player_api|panel_api|get|xmltv)\.php\$ {
        proxy_pass ${PROVIDER_URL};
        proxy_http_version 1.1;
        proxy_set_header Host              ${PROVIDER_HOSTPORT};
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$remote_addr;
        proxy_set_header X-Client-IP       \$remote_addr;
        proxy_set_header User-Agent        \$http_user_agent;
        proxy_set_header Connection        \"\";
        proxy_ssl_server_name on;
        proxy_connect_timeout 10s;
        proxy_read_timeout    90s;
        proxy_buffering on;
        proxy_buffer_size 64k;
        proxy_buffers 16 64k;
    }"
fi

# ----------------------------- ESCRIBIR CONF ---------------------------------
log "Generando configuración de nginx..."
cat > "$CONF" <<EOF
# Generado por xtream-relay.sh — $(date -u +%F' '%T' UTC')
log_format xtream_relay '\$remote_addr [\$time_local] "\$request" '
                        '\$status -> \$sent_http_location "\$http_user_agent"';

server {
    listen ${LISTEN_PORT} ${DEFAULT_SERVER};
    listen [::]:${LISTEN_PORT} ${DEFAULT_SERVER};
    server_name ${SERVER_NAME};

    access_log /var/log/nginx/xtream-relay.access.log xtream_relay;
    error_log  /var/log/nginx/xtream-relay.error.log warn;

    server_tokens off;

    # Comprobación rápida de que el relay está vivo
    location = /relay-health {
        default_type text/plain;
        return 200 "ok\n";
    }
${HYBRID_BLOCK}
${REDIRECT_BLOCK}
}
EOF

rm -f /etc/nginx/sites-enabled/default
ln -sf "$CONF" /etc/nginx/sites-enabled/xtream-relay.conf

nginx -t || die "La configuración de nginx tiene errores."
systemctl enable nginx >/dev/null 2>&1 || true
systemctl reload nginx || systemctl restart nginx

# ----------------------------- CORTAFUEGOS -----------------------------------
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    log "Abriendo puertos en UFW..."
    ufw allow "${LISTEN_PORT}/tcp" >/dev/null
    [[ -n "$DOMAIN" ]] && ufw allow 443/tcp >/dev/null
fi

# ------------------------------- HTTPS ---------------------------------------
if [[ -n "$DOMAIN" ]]; then
    log "Obteniendo certificado Let's Encrypt para $DOMAIN..."
    apt-get install -y certbot python3-certbot-nginx >/dev/null
    # --no-redirect: se mantiene HTTP disponible (muchos reproductores lo usan)
    certbot --nginx -d "$DOMAIN" -m "$EMAIL" --agree-tos --non-interactive --no-redirect \
        || warn "No se pudo obtener el certificado. Comprueba que $DOMAIN apunta a este VPS."
fi

# ------------------------------- PRUEBAS -------------------------------------
log "Probando el relay..."
HEALTH=$(curl -s "http://127.0.0.1:${LISTEN_PORT}/relay-health" || true)
[[ "$HEALTH" == "ok" ]] && log "Health check OK" || warn "Health check falló"

LOC=$(curl -s -o /dev/null -w '%{redirect_url}' \
      "http://127.0.0.1:${LISTEN_PORT}/live/usuario/clave/1.ts" || true)
if [[ "$LOC" == "${PROVIDER_URL}/live/usuario/clave/1.ts" ]]; then
    log "Redirección de streams OK -> $LOC"
else
    warn "La redirección no devolvió lo esperado: '$LOC'"
fi

PUBLIC_IP=$(curl -s --max-time 5 https://api.ipify.org || echo "IP_DEL_VPS")
echo
log "Listo. En la app usa como servidor:"
PORT_SUFFIX=":${LISTEN_PORT}"; [[ "$LISTEN_PORT" == "80" ]] && PORT_SUFFIX=""
echo "      http://${DOMAIN:-$PUBLIC_IP}${PORT_SUFFIX}"
[[ -n "$DOMAIN" ]] && echo "      https://${DOMAIN}"
echo
log "Ver peticiones en vivo (IP cliente -> destino):"
echo "      tail -f /var/log/nginx/xtream-relay.access.log"
log "Para cambiar de proveedor, vuelve a ejecutar con otro PROVIDER_URL."
