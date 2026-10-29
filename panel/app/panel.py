#!/usr/bin/env python3
"""8KPROweb — panel de activación de dispositivos (Python 3.12; biblioteca estándar + python3-maxminddb opcional).

Un único proceso con:
  * API para la app:   POST /api/v1/register   GET /api/v1/config   POST /api/v1/stats
  * Web de administración en /admin (login, lista, activar por código, editar, ajustes,
    "En directo" con lo que ve cada dispositivo, "Mapa" con su ubicación aproximada por IP y
    "DNS" con el estado e historial del DNS anti-bloqueos (evade-proxy) si está instalado en el VPS)

Escucha en 127.0.0.1:8080 detrás de Nginx, que pone el HTTPS. Datos en SQLite.

Archivos:
  /etc/8kpro-panel/config.json   {"admin_user", "admin_hash", "secret"}   (lo crea el instalador)
  /var/lib/8kpro-panel/panel.db  dispositivos + ajustes (DNS global) + sesiones de reproducción
  /var/lib/8kpro-panel/geo/dbip-city-lite.mmdb   geolocalización por IP (DB-IP Lite, CC BY 4.0; opcional)
  /opt/8kpro-panel/static/leaflet/               Leaflet (mapa), servido en /static/leaflet/

Utilidades (las usa el instalador):
  python3 panel.py hash            < contraseña por stdin   -> imprime el hash
  python3 panel.py set-dns URL     -> fija la DNS global
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import html
import json
import os
import secrets
import sqlite3
import sys
import threading
import time
import ipaddress
import mimetypes
import urllib.request
from datetime import datetime
from zoneinfo import ZoneInfo
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

PANEL_VERSION = "1.10"
CONFIG_PATH = os.environ.get("PANEL_CONFIG", "/etc/8kpro-panel/config.json")
DB_PATH = os.environ.get("PANEL_DB", "/var/lib/8kpro-panel/panel.db")
LISTEN = (os.environ.get("PANEL_HOST", "127.0.0.1"), int(os.environ.get("PANEL_PORT", "8080")))
# Detrás de Nginx la cookie va con Secure; en pruebas locales sin TLS se puede desactivar.
COOKIE_SECURE = os.environ.get("PANEL_INSECURE_COOKIE") != "1"
GEO_DB = os.environ.get("PANEL_GEO_DB", "/var/lib/8kpro-panel/geo/dbip-city-lite.mmdb")
STATIC_DIR = os.environ.get("PANEL_STATIC", os.path.join(os.path.dirname(os.path.abspath(__file__)), "static"))

# Estadísticas de reproducción: la app manda un latido cada 30 s; sin noticias en LIVE_WINDOW se da por parado.
LIVE_WINDOW = 75
PLAYS_KEEP_DAYS = 7
# Horas y fechas del panel en la zona del administrador (el VPS suele estar en UTC).
try:
    TZ = ZoneInfo(os.environ.get("PANEL_TZ", "Europe/Madrid"))
except Exception:
    TZ = None
# Periodos de «Estadísticas»: clave -> (segundos, etiqueta, tamaño de cada barra del gráfico en segundos)
STAT_RANGES = {"1h": (3600, "Última hora", 300), "6h": (6 * 3600, "6 horas", 1800), "24h": (86400, "24 horas", 3600),
               "3d": (3 * 86400, "3 días", 6 * 3600), "7d": (7 * 86400, "7 días", 12 * 3600)}
# DNS anti-bloqueos (evade-proxy, instalado con vps/instalar-dns-evade.sh): el panel lee sus métricas cada minuto.
DNS_METRICS_URL = os.environ.get("PANEL_DNS_METRICS", "http://127.0.0.1:5339/metrics")
DNS_DOH_URL = os.environ.get("PANEL_DNS_DOH", "http://127.0.0.1:4053/dns-query")
DNS_SAMPLE_EVERY = 60
DNS_KEEP_DAYS = 31
DNS_RANGES = {"6h": (6 * 3600, "6 horas", 900), "24h": (86400, "24 horas", 3600), "3d": (3 * 86400, "3 días", 3 * 3600),
              "7d": (7 * 86400, "7 días", 6 * 3600), "30d": (30 * 86400, "30 días", 86400)}
MIN_WATCH_FOR_RANKING = 120  # un canal entra en el ranking con al menos 2 min vistos en el periodo
PLAY_STATES = ("playing", "buffering", "paused", "error", "stopped")
GEO_CREDIT = '<p style="text-align:center"><small><a href="https://db-ip.com">IP Geolocation by DB-IP</a></small></p>'
STATE_LABEL = {"playing": "Reproduciendo", "buffering": "Cargando", "paused": "En pausa", "error": "Error", "stopped": "Parado"}

SESSION_COOKIE = "panel_session"
SESSION_TTL = 12 * 3600
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # sin 0/O ni 1/I: se leen bien en la TV
CODE_LEN = 6
MAX_BODY = 16 * 1024

STATUS_LABEL = {"pending": "Pendiente", "active": "Activo", "disabled": "Desactivado", "expired": "Caducado"}

# ------------------------------------------------------------------ utilidades

def now() -> int:
    return int(time.time())


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return "scrypt$" + base64.b64encode(salt).decode() + "$" + base64.b64encode(digest).decode()


def check_password(password: str, stored: str) -> bool:
    try:
        _, salt_b64, digest_b64 = stored.split("$")
        salt, expected = base64.b64decode(salt_b64), base64.b64decode(digest_b64)
    except ValueError:
        return False
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return hmac.compare_digest(digest, expected)


def load_config() -> dict:
    with open(CONFIG_PATH, encoding="utf-8") as f:
        return json.load(f)


def clip(value, n: int = 120):
    if value is None:
        return None
    return str(value)[:n]


def normalize_url(url: str) -> str:
    url = (url or "").strip().rstrip("/")
    if url and not url.lower().startswith(("http://", "https://")):
        url = "http://" + url
    return url


def e(value) -> str:
    return html.escape("" if value is None else str(value))


def fmt_time(ts) -> str:
    if not ts:
        return "—"
    return datetime.fromtimestamp(int(ts), TZ).strftime("%d/%m/%Y %H:%M")


def fmt_dur(seconds) -> str:
    s = max(0, int(seconds or 0))
    h, m, sec = s // 3600, s % 3600 // 60, s % 60
    if h:
        return f"{h} h {m:02d} min"
    if m:
        return f"{m} min {sec:02d} s"
    return f"{sec} s"


def as_int(value, lo: int = 0, hi: int = 10**9):
    try:
        return max(lo, min(hi, int(value)))
    except (TypeError, ValueError):
        return None


# ------------------------------------------------------------------ geolocalización por IP

_geo_lock = threading.Lock()
_geo_reader = None
_geo_mtime = 0.0
_geo_cache: dict[str, dict | None] = {}


def _reader():
    """El lector de la base de datos MMDB; se reabre solo cuando el archivo cambia (actualización mensual)."""
    global _geo_reader, _geo_mtime
    try:
        mtime = os.path.getmtime(GEO_DB)
    except OSError:
        return None
    if _geo_reader is None or mtime != _geo_mtime:
        try:
            import maxminddb  # python3-maxminddb
        except ImportError:
            return None
        try:
            if _geo_reader is not None:
                _geo_reader.close()
            _geo_reader = maxminddb.open_database(GEO_DB)
            _geo_mtime = mtime
            _geo_cache.clear()
        except Exception as exc:
            sys.stderr.write(f"geo: no se pudo abrir {GEO_DB}: {exc!r}\n")
            _geo_reader = None
    return _geo_reader


def _name(node) -> str:
    names = (node or {}).get("names") or {}
    return names.get("es") or names.get("en") or ""


def geo(ip: str | None) -> dict | None:
    """Ubicación aproximada de una IP pública: {city, region, country, cc, lat, lon}; None si no se sabe."""
    if not ip:
        return None
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return None
    if not addr.is_global:
        return None
    with _geo_lock:
        if ip in _geo_cache:
            return _geo_cache[ip]
        reader = _reader()
        if reader is None:
            return None
        try:
            rec = reader.get(ip) or {}
        except Exception:
            rec = {}
        loc = rec.get("location") or {}
        out = None
        if loc.get("latitude") is not None and loc.get("longitude") is not None:
            subs = rec.get("subdivisions") or [{}]
            out = {"city": _name(rec.get("city")), "region": _name(subs[0]), "country": _name(rec.get("country")),
                   "cc": (rec.get("country") or {}).get("iso_code", ""),
                   "lat": round(float(loc["latitude"]), 4), "lon": round(float(loc["longitude"]), 4)}
        if len(_geo_cache) > 20000:
            _geo_cache.clear()
        _geo_cache[ip] = out
        return out


def geo_label(ip: str | None) -> str:
    g = geo(ip)
    if not g:
        return ""
    return ", ".join(x for x in (g["city"], g["country"]) if x)


def geo_available() -> bool:
    with _geo_lock:
        return _reader() is not None


# ------------------------------------------------------------------ base de datos

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id     TEXT NOT NULL UNIQUE,
    token_hash    TEXT NOT NULL,
    code          TEXT NOT NULL UNIQUE,
    status        TEXT NOT NULL DEFAULT 'pending',   -- pending | active | disabled
    model         TEXT, manufacturer TEXT, android TEXT, app_version TEXT,
    ip            TEXT,
    server_url    TEXT,                              -- NULL/'' = usa la DNS global
    username      TEXT, password TEXT,
    notes         TEXT,
    expires_at    INTEGER,                           -- epoch s; NULL = sin caducidad
    created_at    INTEGER NOT NULL,
    last_seen     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_status ON devices(status);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT);
-- Una fila por "sesión de visionado" (un canal, película o episodio) que manda la app.
CREATE TABLE IF NOT EXISTS plays (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id       INTEGER NOT NULL,                  -- devices.id
    session_id      TEXT NOT NULL UNIQUE,
    title           TEXT, kind TEXT,                   -- kind: live | vod
    state           TEXT,                              -- playing | buffering | paused | error | stopped
    started_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    watch_s         INTEGER DEFAULT 0,
    buffering_count INTEGER DEFAULT 0,
    buffering_s     INTEGER DEFAULT 0,
    startup_ms      INTEGER,
    video TEXT, engine TEXT, ip TEXT
);
CREATE INDEX IF NOT EXISTS idx_plays_device ON plays(device_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_plays_updated ON plays(updated_at);
-- Una fila por minuto con los contadores del DNS anti-bloqueos (acumulados desde que arrancó evade-proxy).
CREATE TABLE IF NOT EXISTS dns_samples (
    ts          INTEGER PRIMARY KEY,
    processed   INTEGER, evaded INTEGER, upfail INTEGER, dropped INTEGER,
    blocked_v4  INTEGER, blocked_v6 INTEGER, evadable INTEGER, cf_ranges INTEGER
);
"""

_db_lock = threading.Lock()


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    with db() as conn:
        conn.executescript(SCHEMA)
        conn.execute("PRAGMA journal_mode=WAL")
        # v1.5+: proveedor de backup de cada dispositivo (DNS, usuario y contraseña propios).
        cols = {r[1] for r in conn.execute("PRAGMA table_info(devices)")}
        for col in ("backup_server_url", "backup_username", "backup_password"):
            if col not in cols:
                conn.execute(f"ALTER TABLE devices ADD COLUMN {col} TEXT")
        if "backup_enabled" not in cols:  # v1.7: el tick que activa o desactiva el backup del dispositivo
            conn.execute("ALTER TABLE devices ADD COLUMN backup_enabled INTEGER NOT NULL DEFAULT 1")
        if "hw_id" not in cols:  # v1.8: id del aparato que sobrevive a desinstalar la app (mismo código al reinstalar)
            conn.execute("ALTER TABLE devices ADD COLUMN hw_id TEXT")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_devices_hw ON devices(hw_id)")


def valid_hw(value) -> str | None:
    """El id de aparato que manda la app (hash hexadecimal); None si no viene o no tiene buena pinta."""
    v = str(value or "").strip().lower()
    return v if 16 <= len(v) <= 64 and all(c in "0123456789abcdef" for c in v) else None


def backup_for(conn, row) -> dict | None:
    """El proveedor de backup del dispositivo; None si está desactivado o le falta la DNS, el usuario o la contraseña."""
    if not row["backup_enabled"]:
        return None
    url, user, pw = row["backup_server_url"], row["backup_username"], row["backup_password"]
    if url and user and pw:
        return {"server_url": url, "username": user, "password": pw}
    return None


_last_prune = 0.0


def prune_plays(conn) -> None:
    """Borra el historial de más de PLAYS_KEEP_DAYS días (como mucho una vez por hora)."""
    global _last_prune
    if time.time() - _last_prune < 3600:
        return
    _last_prune = time.time()
    conn.execute("DELETE FROM plays WHERE updated_at < ?", (now() - PLAYS_KEEP_DAYS * 86400,))


def live_plays(conn):
    """Lo que se está viendo ahora: la última sesión no parada de cada dispositivo con latido reciente."""
    return conn.execute(
        "SELECT p.*, d.code, d.username, d.notes, d.model, d.manufacturer, d.ip AS dev_ip FROM plays p "
        "JOIN devices d ON d.id = p.device_id "
        "WHERE p.updated_at >= ? AND p.state != 'stopped' AND p.id = ("
        "  SELECT p2.id FROM plays p2 WHERE p2.device_id = p.device_id ORDER BY p2.updated_at DESC LIMIT 1) "
        "ORDER BY p.started_at", (now() - LIVE_WINDOW,)).fetchall()


def get_setting(conn, key: str, default: str = "") -> str:
    row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else default


def set_setting(conn, key: str, value: str) -> None:
    conn.execute("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                 (key, value))


# DNS con la que la app resuelve nombres (servidor Xtream, logos, EPG...). Se envía en cada /api/v1/config;
# la app prueba la primera y, si no responde, la segunda. Se comprueba cada vez que se abre la app.
APP_DNS_DEFAULT = ("185.253.152.214", "85.208.114.51")


def app_dns(conn) -> list[str]:
    out = []
    for i, default in enumerate(APP_DNS_DEFAULT, start=1):
        ip = get_setting(conn, f"app_dns_{i}", default).strip()
        if ip and ip not in out:
            out.append(ip)
    return out


def valid_ip(text: str) -> str | None:
    try:
        return str(ipaddress.ip_address(text.strip()))
    except ValueError:
        return None


def new_code(conn) -> str:
    while True:
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LEN))
        if not conn.execute("SELECT 1 FROM devices WHERE code = ?", (code,)).fetchone():
            return code


def effective_status(row) -> str:
    st = row["status"]
    if st == "active" and row["expires_at"] and int(row["expires_at"]) < now():
        return "expired"
    return st


# ------------------------------------------------------------------ sesión admin

def sign(value: str, secret: str) -> str:
    return value + "." + hmac.new(secret.encode(), value.encode(), hashlib.sha256).hexdigest()


def make_session(cfg: dict) -> str:
    return sign(f"{cfg['admin_user']}:{now() + SESSION_TTL}", cfg["secret"])


def valid_session(raw: str | None, cfg: dict) -> bool:
    if not raw or "." not in raw:
        return False
    value = raw.rpartition(".")[0]
    if not hmac.compare_digest(sign(value, cfg["secret"]), raw):
        return False
    user, _, exp = value.rpartition(":")
    return exp.isdigit() and int(exp) >= now() and user == cfg["admin_user"]


_failed: dict[str, list[float]] = {}
_failed_lock = threading.Lock()


def throttled(ip: str) -> bool:
    with _failed_lock:
        recent = [t for t in _failed.get(ip, []) if t > time.time() - 900]
        _failed[ip] = recent
        return len(recent) >= 8


def note_failure(ip: str) -> None:
    with _failed_lock:
        _failed.setdefault(ip, []).append(time.time())


# ------------------------------------------------------------------ HTML

CSS = """
:root{--bg:#14121c;--card:#1f1c2b;--line:#332f45;--text:#f2eff8;--muted:#a49fb8;--accent:#ffb95c;--ok:#5cd18a;--bad:#ff6b6b;--warn:#f0c04a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
a{color:var(--accent);text-decoration:none}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:14px 24px;border-bottom:1px solid var(--line);flex-wrap:wrap}
header b{font-size:18px}header b span{color:var(--accent)}header nav{display:flex;gap:8px}
main{max-width:1200px;margin:0 auto;padding:24px 16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:20px;margin-bottom:20px}
input,select{width:100%;background:#15131d;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:10px;font:inherit}
input[type=checkbox]{width:auto}
label{display:block;color:var(--muted);font-size:13px;margin:12px 0 4px}
button,.btn{background:var(--accent);color:#1b1408;border:0;border-radius:8px;padding:10px 18px;font:inherit;font-weight:700;cursor:pointer;display:inline-block}
.btn.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}.btn.danger{background:var(--bad);color:#fff}
table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:10px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600;font-size:13px}small{color:var(--muted)}
.code{font-family:ui-monospace,Consolas,monospace;font-weight:700;letter-spacing:.08em}
.pill{display:inline-block;padding:2px 10px;border-radius:99px;font-size:12px;font-weight:700}
.pending{background:#3d3418;color:var(--warn)}.active{background:#173a26;color:var(--ok)}.disabled,.expired{background:#3c1c1f;color:var(--bad)}
.row{display:flex;gap:12px;flex-wrap:wrap}.row>*{flex:1;min-width:200px}.err{color:var(--bad)}.flash{color:var(--ok)}
.table-wrap{overflow-x:auto}.check{display:flex;gap:8px;align-items:center;margin-top:12px;color:var(--text)}
.playing{background:#173a26;color:var(--ok)}.buffering{background:#3d3418;color:var(--warn)}.paused,.stopped{background:#2a2738;color:var(--muted)}.error{background:#3c1c1f;color:var(--bad)}
.stats{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:20px}.stat{flex:1;min-width:150px;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 18px}
.stat b{display:block;font-size:28px}.stat small{font-size:13px}.bad{color:var(--bad)}.warn{color:var(--warn)}
#map{height:68vh;min-height:380px;border-radius:12px;border:1px solid var(--line)}
.leaflet-tile-pane{filter:invert(1) hue-rotate(200deg) brightness(.85) contrast(.9) saturate(.4)}
.leaflet-popup-content{font:14px/1.45 system-ui,sans-serif;color:#1b1820}.leaflet-popup-content a{color:#9a5b00}
.legend{display:flex;gap:16px;flex-wrap:wrap;margin:10px 0 0}.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}
"""


def layout(title: str, body: str, logged: bool = True, head: str = "") -> str:
    nav = ('<nav><a class="btn ghost" href="/admin">Dispositivos</a><a class="btn ghost" href="/admin/live">En directo</a>'
           '<a class="btn ghost" href="/admin/stats">Estadísticas</a>'
           '<a class="btn ghost" href="/admin/map">Mapa</a><a class="btn ghost" href="/admin/dns">DNS</a>'
           '<a class="btn ghost" href="/admin/settings">Ajustes</a>'
           '<a class="btn ghost" href="/admin/logout">Salir</a></nav>') if logged else ""
    return (f'<!doctype html><html lang="es"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">'
            f'<title>{e(title)} · 8K PRO</title><style>{CSS}</style>{head}</head><body>'
            f'<header><b>8K <span>PRO</span> · Panel</b>{nav}</header><main>{body}'
            f'<p style="text-align:center"><small>Panel v{PANEL_VERSION}</small></p></main></body></html>')


# ------------------------------------------------------------------ DNS anti-bloqueos

def dns_metrics() -> dict | None:
    """Métricas de evade-proxy como {nombre o nombre{etiquetas}: valor}; None si no responde."""
    try:
        with urllib.request.urlopen(DNS_METRICS_URL, timeout=3) as resp:
            text = resp.read(256 * 1024).decode("utf-8", "replace")
    except Exception:
        return None
    out = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        key, _, value = line.rpartition(" ")
        try:
            out[key] = float(value)
        except ValueError:
            pass
    return out


def _msum(m: dict, name: str) -> int:
    return int(sum(v for k, v in m.items() if k == name or k.startswith(name + "{")))


def dns_snapshot(m: dict) -> dict:
    return {"processed": _msum(m, "xdp_evade_queries_processed"), "evaded": _msum(m, "xdp_evade_queries_total"),
            "upfail": _msum(m, "xdp_evade_upstream_failures_total"), "dropped": _msum(m, "xdp_evade_dropped_total"),
            "blocked_v4": int(m.get('xdp_evade_blocked_ips{family="ipv4"}', 0)),
            "blocked_v6": int(m.get('xdp_evade_blocked_ips{family="ipv6"}', 0)),
            "evadable": _msum(m, "xdp_evade_evadable_ips"), "cf_ranges": _msum(m, "xdp_evade_cloudflare_ranges")}


def dns_doh_ok() -> bool:
    """¿Responde el DoH (Blocky)? Consulta real de example.com tipo A."""
    try:
        with urllib.request.urlopen(DNS_DOH_URL + "?dns=AAABAAABAAAAAAAAB2V4YW1wbGUDY29tAAABAAE", timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False


def dns_sampler() -> None:
    """Hilo de fondo: guarda una muestra por minuto mientras evade-proxy responda; borra lo de más de DNS_KEEP_DAYS."""
    last_prune = 0.0
    while True:
        try:
            m = dns_metrics()
            if m is not None:
                snap = dns_snapshot(m)
                with db() as conn:
                    conn.execute("INSERT OR REPLACE INTO dns_samples(ts, processed, evaded, upfail, dropped, blocked_v4, "
                                 "blocked_v6, evadable, cf_ranges) VALUES(?,?,?,?,?,?,?,?,?)",
                                 (now(), snap["processed"], snap["evaded"], snap["upfail"], snap["dropped"],
                                  snap["blocked_v4"], snap["blocked_v6"], snap["evadable"], snap["cf_ranges"]))
                    if time.time() - last_prune > 3600:
                        last_prune = time.time()
                        conn.execute("DELETE FROM dns_samples WHERE ts < ?", (now() - DNS_KEEP_DAYS * 86400,))
        except Exception as exc:
            sys.stderr.write(f"dns_sampler: {exc!r}\n")
        time.sleep(DNS_SAMPLE_EVERY)


def fmt_num(n) -> str:
    return f"{int(n or 0):,}".replace(",", ".")


# ------------------------------------------------------------------ servidor

class Handler(BaseHTTPRequestHandler):
    server_version = "8KPRO-Panel"
    sys_version = ""

    # ---- helpers de petición/respuesta
    def log_message(self, fmt, *args):  # una línea por petición al journal, sin cuerpos
        sys.stderr.write("%s %s\n" % (self.client_ip(), fmt % args))

    def client_ip(self) -> str:
        return self.headers.get("X-Real-IP") or self.client_address[0]

    def send(self, status: int, body: bytes, ctype: str, extra: dict | None = None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        # same-origin (no "no-referrer"): with no-referrer the browser sends "Origin: null" on our own
        # form posts, which the CSRF check below would then refuse.
        self.send_header("Referrer-Policy", "same-origin")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def json(self, status: int, data: dict):
        self.send(status, json.dumps(data).encode(), "application/json; charset=utf-8")

    def html(self, page: str, status: int = 200, extra: dict | None = None):
        self.send(status, page.encode(), "text/html; charset=utf-8", extra)

    def redirect(self, location: str, extra: dict | None = None):
        headers = {"Location": location}
        headers.update(extra or {})
        self.send(303, b"", "text/plain", headers)

    def body_bytes(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            raise ValueError("body too large")
        return self.rfile.read(length) if length else b""

    def form(self) -> dict:
        data = parse_qs(self.body_bytes().decode("utf-8", "replace"), keep_blank_values=True)
        return {k: v[0] for k, v in data.items()}

    def cookie(self, name: str) -> str | None:
        jar = SimpleCookie(self.headers.get("Cookie") or "")
        return jar[name].value if name in jar else None

    def is_admin(self) -> bool:
        try:
            return valid_session(self.cookie(SESSION_COOKIE), load_config())
        except Exception:
            return False

    # ---- enrutado
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        path = url.path.rstrip("/") or "/"
        try:
            if path == "/api/v1/config":
                return self.api_config()
            if path == "/api/v1/ping":
                return self.json(200, {"ok": True})
            if path.startswith("/static/"):
                return self.static(path)
            if path == "/":
                return self.redirect("/admin")
            if path == "/admin/login":
                return self.page_login(q.get("error", ""))
            if path == "/admin/logout":
                return self.redirect("/admin/login", {"Set-Cookie": f"{SESSION_COOKIE}=; Max-Age=0; Path=/; HttpOnly; SameSite=Strict"})
            if not path.startswith("/admin"):
                return self.send(404, b"Not found", "text/plain")
            if not self.is_admin():
                return self.redirect("/admin/login")
            if path == "/admin":
                return self.page_devices(q)
            if path == "/admin/code":
                return self.find_code(q.get("code", ""))
            if path == "/admin/settings":
                return self.page_settings(q.get("msg", ""))
            if path == "/admin/live":
                return self.page_live()
            if path == "/admin/stats":
                return self.page_stats(q)
            if path == "/admin/map":
                return self.page_map()
            if path == "/admin/dns":
                return self.page_dns(q)
            if path == "/admin/api/map":
                return self.json(200, {"points": self.map_points()})
            if path.startswith("/admin/device/"):
                return self.page_device(path.rsplit("/", 1)[-1])
            return self.send(404, b"Not found", "text/plain")
        except Exception as exc:  # nunca filtrar trazas al cliente
            sys.stderr.write(f"ERROR GET {path}: {exc!r}\n")
            return self.send(500, b"Error interno", "text/plain; charset=utf-8")

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        try:
            if path == "/api/v1/register":
                return self.api_register()
            if path == "/api/v1/stats":
                return self.api_stats()
            if path == "/admin/login":
                return self.do_login()
            if not self.is_admin():
                return self.redirect("/admin/login")
            # CSRF: la cookie de sesión es SameSite=Strict, así que otra web no puede enviarla en un POST.
            if path == "/admin/settings":
                return self.save_settings()
            if path == "/admin/settings/app-dns":
                return self.save_app_dns()
            if path.startswith("/admin/device/") and path.endswith("/delete"):
                return self.delete_device(path.split("/")[-2])
            if path.startswith("/admin/device/"):
                return self.save_device(path.rsplit("/", 1)[-1])
            return self.send(404, b"Not found", "text/plain")
        except ValueError:
            return self.send(400, b"Peticion no valida", "text/plain")
        except Exception as exc:
            sys.stderr.write(f"ERROR POST {path}: {exc!r}\n")
            return self.send(500, b"Error interno", "text/plain; charset=utf-8")

    # ---- API de la app
    def api_register(self):
        try:
            data = json.loads(self.body_bytes() or b"{}")
        except json.JSONDecodeError:
            return self.json(400, {"error": "json"})
        device_id = str(data.get("device_id") or "")
        token = str(data.get("token") or "")
        if not (16 <= len(device_id) <= 64 and 32 <= len(token) <= 128):
            return self.json(400, {"error": "invalid"})
        info = (clip(data.get("model")), clip(data.get("manufacturer")), clip(data.get("android"), 20),
                clip(data.get("app_version"), 40))
        hw = valid_hw(data.get("hardware_id"))
        with _db_lock, db() as conn:
            row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
            if row:
                if not hmac.compare_digest(row["token_hash"], sha256(token)):
                    return self.json(409, {"error": "device_id in use"})
                conn.execute("UPDATE devices SET model=?, manufacturer=?, android=?, app_version=?, ip=?, last_seen=?,"
                             " hw_id=COALESCE(?, hw_id) WHERE id=?", (*info, self.client_ip(), now(), hw, row["id"]))
                return self.json(200, {"code": row["code"]})
            # Reinstalada en el mismo aparato: el mismo dispositivo de siempre (código, cuenta, backup…)
            # con el id y el token nuevos de esta instalación.
            same = conn.execute("SELECT * FROM devices WHERE hw_id = ? ORDER BY COALESCE(last_seen, created_at) DESC LIMIT 1",
                                (hw,)).fetchone() if hw else None
            if same:
                conn.execute("UPDATE devices SET device_id=?, token_hash=?, model=?, manufacturer=?, android=?, app_version=?,"
                             " ip=?, last_seen=? WHERE id=?",
                             (device_id, sha256(token), *info, self.client_ip(), now(), same["id"]))
                return self.json(200, {"code": same["code"]})
            code = new_code(conn)
            conn.execute("INSERT INTO devices (device_id, token_hash, code, model, manufacturer, android, app_version, ip, created_at, last_seen, hw_id)"
                         " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                         (device_id, sha256(token), code, *info, self.client_ip(), now(), now(), hw))
            return self.json(200, {"code": code})

    def api_config(self):
        device_id = self.headers.get("X-Device-Id", "")
        token = self.headers.get("X-Device-Token", "")
        version = clip(self.headers.get("X-App-Version", ""), 40)
        with _db_lock, db() as conn:
            row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
            if not row or not hmac.compare_digest(row["token_hash"], sha256(token)):
                # La app vuelve a registrarse al recibir 401 (p. ej. si borraste el dispositivo).
                return self.json(401, {"status": "unknown"})
            conn.execute("UPDATE devices SET last_seen=?, ip=?, app_version=COALESCE(NULLIF(?, ''), app_version),"
                         " hw_id=COALESCE(?, hw_id) WHERE id=?",
                         (now(), self.client_ip(), version, valid_hw(self.headers.get("X-Hardware-Id")), row["id"]))
            status = effective_status(row)
            server = row["server_url"] or get_setting(conn, "default_server_url")
            if status == "active" and not (server and row["username"] and row["password"]):
                status = "pending"
            out = {"status": status, "code": row["code"], "dns": app_dns(conn)}
            if status == "active":
                out.update(server_url=server, username=row["username"], password=row["password"])
                backup = backup_for(conn, row)
                if backup:
                    out["backup"] = backup
            return self.json(200, out)

    def api_stats(self):
        """Latido de reproducción de la app: qué ve, si hay cortes y cuánto lleva. Ver PlaybackStats.kt."""
        device_id = self.headers.get("X-Device-Id", "")
        token = self.headers.get("X-Device-Token", "")
        try:
            data = json.loads(self.body_bytes() or b"{}")
        except json.JSONDecodeError:
            return self.json(400, {"error": "json"})
        if not isinstance(data, dict):
            return self.json(400, {"error": "json"})
        sid = str(data.get("session_id") or "")
        if not (8 <= len(sid) <= 64):
            return self.json(400, {"error": "invalid"})
        state = data.get("state") if data.get("state") in PLAY_STATES else "playing"
        kind = "vod" if data.get("kind") == "vod" else "live"
        watch_s = as_int(data.get("watch_s"), 0, 7 * 86400) or 0
        values = (clip(data.get("title"), 160), kind, state, watch_s,
                  as_int(data.get("buffering_count"), 0, 100000) or 0, as_int(data.get("buffering_s"), 0, 7 * 86400) or 0,
                  as_int(data.get("startup_ms"), 0, 600000), clip(data.get("video"), 40), clip(data.get("engine"), 40))
        ip = self.client_ip()
        with _db_lock, db() as conn:
            row = conn.execute("SELECT id, token_hash FROM devices WHERE device_id = ?", (device_id,)).fetchone()
            if not row or not hmac.compare_digest(row["token_hash"], sha256(token)):
                return self.json(401, {"status": "unknown"})
            t = now()
            existing = conn.execute("SELECT device_id FROM plays WHERE session_id = ?", (sid,)).fetchone()
            if existing and existing["device_id"] != row["id"]:
                return self.json(409, {"error": "session"})
            if existing:
                conn.execute("UPDATE plays SET title=?, kind=?, state=?, watch_s=?, buffering_count=?, buffering_s=?,"
                             " startup_ms=COALESCE(?, startup_ms), video=COALESCE(?, video), engine=COALESCE(?, engine),"
                             " updated_at=?, ip=? WHERE session_id=?", (*values, t, ip, sid))
            else:
                conn.execute("INSERT INTO plays (title, kind, state, watch_s, buffering_count, buffering_s, startup_ms, video, engine,"
                             " device_id, session_id, started_at, updated_at, ip) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                             (*values, row["id"], sid, t - watch_s, t, ip))
            conn.execute("UPDATE devices SET last_seen=?, ip=? WHERE id=?", (t, ip, row["id"]))
            prune_plays(conn)
        return self.json(200, {"ok": True})

    # ---- estáticos (Leaflet)
    def static(self, path: str):
        rel = os.path.normpath(path[len("/static/"):]).lstrip("/\\")
        full = os.path.join(STATIC_DIR, rel)
        full = os.path.abspath(full)
        if rel.startswith("..") or not os.path.isfile(full) or not full.startswith(os.path.abspath(STATIC_DIR) + os.sep):
            return self.send(404, b"Not found", "text/plain")
        ctype = mimetypes.guess_type(full)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        with open(full, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "public, max-age=604800")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    # ---- admin: en directo
    def page_live(self):
        with db() as conn:
            live = live_plays(conn)
            history = conn.execute(
                "SELECT p.*, d.code, d.username FROM plays p JOIN devices d ON d.id = p.device_id "
                "ORDER BY p.updated_at DESC LIMIT 60").fetchall()
            active = conn.execute("SELECT COUNT(*) FROM devices WHERE status='active'").fetchone()[0]
            online = conn.execute("SELECT COUNT(*) FROM devices WHERE last_seen >= ?", (now() - 600,)).fetchone()[0]
        cutting = sum(1 for r in live if r["state"] == "buffering")
        trs = []
        for r in live:
            cuts = f'{r["buffering_count"]} <small>({fmt_dur(r["buffering_s"])})</small>' if r["buffering_count"] else "0"
            cls = "bad" if r["buffering_count"] >= 5 else ("warn" if r["buffering_count"] else "")
            trs.append(f"""<tr><td><a class="code" href="/admin/device/{r['device_id']}">{e(r['code'])}</a><br><small>{e(r['username']) or '—'}</small></td>
<td>{e(r['title']) or '—'}<br><small>{'Directo' if r['kind'] == 'live' else 'Película/serie'}{(' · ' + e(r['video'])) if r['video'] else ''}</small></td>
<td><span class="pill {e(r['state'])}">{STATE_LABEL.get(r['state'], e(r['state']))}</span></td>
<td>{fmt_dur(r['watch_s'] + max(0, now() - r['updated_at']))}</td>
<td class="{cls}">{cuts}</td>
<td>{(str(r['startup_ms'] // 100 / 10) + ' s') if r['startup_ms'] is not None else '—'}</td>
<td>{e(geo_label(r['ip'])) or '—'}<br><small>{e(r['ip'])}</small></td></tr>""")
        table = "".join(trs) or '<tr><td colspan="7"><small>Nadie está viendo nada ahora mismo.</small></td></tr>'
        hrs = []
        for r in history:
            hrs.append(f"""<tr><td>{fmt_time(r['started_at'])}</td><td><a class="code" href="/admin/device/{r['device_id']}">{e(r['code'])}</a> <small>{e(r['username'])}</small></td>
<td>{e(r['title'])}</td><td>{fmt_dur(r['watch_s'])}</td><td>{r['buffering_count']} <small>({fmt_dur(r['buffering_s'])})</small></td>
<td><span class="pill {e(r['state'])}">{STATE_LABEL.get(r['state'], e(r['state']))}</span></td></tr>""")
        hist = "".join(hrs) or '<tr><td colspan="6"><small>Sin historial todavía.</small></td></tr>'
        return self.html(layout("En directo", f"""<div class="stats">
<div class="stat"><b>{len(live)}</b><small>viendo ahora</small></div>
<div class="stat"><b class="{'warn' if cutting else ''}">{cutting}</b><small>cargando (buffering) ahora</small></div>
<div class="stat"><b>{online}</b><small>conectados en los últimos 10 min</small></div>
<div class="stat"><b>{active}</b><small>dispositivos activos</small></div></div>
<div class="card"><h2 style="margin-top:0">Viendo ahora</h2><p><small>Se actualiza sola cada 15 s. «Cortes» = veces que se ha parado a cargar después de empezar (y tiempo total cargando). Historial de 7 días; resumen en <a href="/admin/stats">Estadísticas</a>.</small></p>
<div class="table-wrap"><table><tr><th>Dispositivo</th><th>Contenido</th><th>Estado</th><th>Tiempo viendo</th><th>Cortes</th><th>Arranque</th><th>Ubicación</th></tr>
{table}</table></div></div>
<div class="card"><h2 style="margin-top:0">Últimas sesiones</h2><div class="table-wrap"><table>
<tr><th>Inicio</th><th>Dispositivo</th><th>Contenido</th><th>Duración</th><th>Cortes</th><th>Estado</th></tr>{hist}</table></div></div>
{GEO_CREDIT if geo_available() else ''}""", head='<meta http-equiv="refresh" content="15">'))

    # ---- admin: estadísticas de cortes
    def page_stats(self, q: dict):
        rng = q.get("r") if q.get("r") in STAT_RANGES else "24h"
        kind = q.get("k") if q.get("k") in ("live", "vod", "all") else "live"
        span, label, bucket = STAT_RANGES[rng]
        since = now() - span
        where, args = "p.updated_at >= ?", [since]
        if kind != "all":
            where += " AND p.kind = ?"
            args.append(kind)
        with db() as conn:
            tot = conn.execute(f"SELECT COUNT(*) n, COUNT(DISTINCT p.device_id) devs, COALESCE(SUM(p.watch_s),0) w, "
                               f"COALESCE(SUM(p.buffering_count),0) c, COALESCE(SUM(p.buffering_s),0) b, "
                               f"SUM(CASE WHEN p.buffering_count > 0 THEN 1 ELSE 0 END) withcuts, AVG(p.startup_ms) st, "
                               f"SUM(CASE WHEN p.state='error' THEN 1 ELSE 0 END) errs FROM plays p WHERE {where}", args).fetchone()
            channels = conn.execute(f"SELECT p.title, COUNT(*) n, COUNT(DISTINCT p.device_id) devs, SUM(p.watch_s) w, "
                                    f"SUM(p.buffering_count) c, SUM(p.buffering_s) b, AVG(p.startup_ms) st, "
                                    f"SUM(CASE WHEN p.state='error' THEN 1 ELSE 0 END) errs FROM plays p WHERE {where} "
                                    f"GROUP BY p.title HAVING SUM(p.watch_s) >= ? ORDER BY (SUM(p.buffering_count) * 3600.0 / SUM(p.watch_s)) DESC, "
                                    f"SUM(p.buffering_count) DESC LIMIT 40", args + [MIN_WATCH_FOR_RANKING]).fetchall()
            devices = conn.execute(f"SELECT p.device_id, d.code, d.username, d.ip, COUNT(*) n, SUM(p.watch_s) w, SUM(p.buffering_count) c, "
                                   f"SUM(p.buffering_s) b FROM plays p JOIN devices d ON d.id = p.device_id WHERE {where} "
                                   f"GROUP BY p.device_id HAVING SUM(p.watch_s) >= ? AND SUM(p.buffering_count) > 0 "
                                   f"ORDER BY (SUM(p.buffering_count) * 3600.0 / SUM(p.watch_s)) DESC LIMIT 15",
                                   args + [MIN_WATCH_FOR_RANKING]).fetchall()
            rows = conn.execute(f"SELECT p.updated_at, p.started_at, p.buffering_count FROM plays p WHERE {where}", args).fetchall()

        def per_hour(c, w):
            return (c or 0) * 3600.0 / w if w else 0.0

        def level(rate):
            return "bad" if rate >= 6 else ("warn" if rate >= 2 else "")

        # Gráfico: cortes por intervalo (cada sesión cuenta en el intervalo de su último latido) y sesiones activas.
        start = since - since % bucket
        nb = (now() - start) // bucket + 1
        cuts_b, sess_b = [0] * nb, [0] * nb
        for r in rows:
            i = min(nb - 1, max(0, (r["updated_at"] - start) // bucket))
            cuts_b[i] += r["buffering_count"] or 0
            sess_b[i] += 1
        peak = max(cuts_b) or 1
        W, H, pad = 900, 180, 24
        bw = (W - 2 * pad) / nb
        bars = []
        for i, c in enumerate(cuts_b):
            h = (H - 40) * c / peak
            x = pad + i * bw
            t0 = datetime.fromtimestamp(start + i * bucket, TZ)
            tip = t0.strftime("%d/%m %H:%M") + f" · {c} cortes · {sess_b[i]} sesiones"
            bars.append(f'<rect x="{x + 1:.1f}" y="{H - 20 - h:.1f}" width="{max(bw - 2, 1):.1f}" height="{h:.1f}" rx="2" '
                        f'fill="{"#ff6b6b" if c and c >= peak * .7 else "#ffb95c"}"><title>{e(tip)}</title></rect>')
        step = max(1, nb // 8)
        for i in range(0, nb, step):
            t0 = datetime.fromtimestamp(start + i * bucket, TZ)
            fmt = "%H:%M" if span <= 86400 else "%d/%m %Hh"
            bars.append(f'<text x="{pad + i * bw:.1f}" y="{H - 4}" fill="#a49fb8" font-size="11">{t0.strftime(fmt)}</text>')
        bars.append(f'<text x="{pad}" y="12" fill="#a49fb8" font-size="11">máx. {peak if any(cuts_b) else 0} cortes</text>')
        chart = f'<svg viewBox="0 0 {W} {H}" width="100%" role="img" aria-label="Cortes en el tiempo">{"".join(bars)}</svg>'

        rate_all = per_hour(tot["c"], tot["w"])
        pct_cut = round(100 * (tot["withcuts"] or 0) / tot["n"]) if tot["n"] else 0
        buf_pct = round(100 * (tot["b"] or 0) / tot["w"], 1) if tot["w"] else 0

        def btn(key, text, param, current):
            other = f"&k={kind}" if param == "r" else f"&r={rng}"
            cls = "btn" if key == current else "btn ghost"
            return f'<a class="{cls}" href="/admin/stats?{param}={key}{other}" style="padding:6px 14px">{text}</a>'

        ranges = " ".join(btn(k, v[1], "r", rng) for k, v in STAT_RANGES.items())
        kinds = " ".join(btn(k, t, "k", kind) for k, t in (("live", "Directo"), ("vod", "Películas/series"), ("all", "Todo")))

        ch_rows = []
        for i, r in enumerate(channels, 1):
            rate = per_hour(r["c"], r["w"])
            bp = round(100 * (r["b"] or 0) / r["w"], 1) if r["w"] else 0
            ch_rows.append(f"""<tr><td>{i}</td><td><b>{e(r['title']) or '—'}</b></td><td class="{level(rate)}"><b>{rate:.1f}</b></td>
<td>{r['c'] or 0}</td><td>{fmt_dur(r['b'])} <small>({bp}%)</small></td><td>{fmt_dur(r['w'])}</td><td>{r['n']} <small>/ {r['devs']} disp.</small></td>
<td>{(str(round((r['st'] or 0) / 1000, 1)) + ' s') if r['st'] is not None else '—'}</td><td>{r['errs'] or 0}</td></tr>""")
        ch_table = "".join(ch_rows) or '<tr><td colspan="9"><small>Sin datos suficientes en este periodo.</small></td></tr>'
        dev_rows = "".join(f"""<tr><td><a class="code" href="/admin/device/{r['device_id']}">{e(r['code'])}</a> <small>{e(r['username'])}</small></td>
<td class="{level(per_hour(r['c'], r['w']))}"><b>{per_hour(r['c'], r['w']):.1f}</b></td><td>{r['c']}</td><td>{fmt_dur(r['w'])}</td>
<td>{e(geo_label(r['ip'])) or '—'} <small>{e(r['ip'])}</small></td></tr>""" for r in devices) \
            or '<tr><td colspan="5"><small>Ningún dispositivo con cortes en este periodo.</small></td></tr>'

        return self.html(layout("Estadísticas", f"""<div class="card" style="display:flex;flex-wrap:wrap;gap:12px 24px;align-items:center;justify-content:space-between">
<div style="display:flex;flex-wrap:wrap;gap:6px;align-items:center"><b>Periodo:</b> {ranges}</div>
<div style="display:flex;flex-wrap:wrap;gap:6px;align-items:center"><b>Tipo:</b> {kinds}</div></div>
<div class="stats">
<div class="stat"><b class="{level(rate_all)}">{rate_all:.1f}</b><small>cortes por hora de visionado</small></div>
<div class="stat"><b>{tot['c']}</b><small>cortes en total ({fmt_dur(tot['b'])} cargando, {buf_pct}% del tiempo)</small></div>
<div class="stat"><b>{pct_cut}%</b><small>de sesiones con algún corte ({tot['n']} sesiones)</small></div>
<div class="stat"><b>{fmt_dur(tot['w'])}</b><small>vistas por {tot['devs']} dispositivo(s)</small></div>
<div class="stat"><b>{(str(round((tot['st'] or 0) / 1000, 1)) + ' s') if tot['st'] is not None else '—'}</b><small>arranque medio · {tot['errs'] or 0} errores</small></div></div>
<div class="card"><h2 style="margin-top:0">Cortes en el tiempo · {label}</h2>{chart}
<p><small>Pasa el ratón por una barra para ver el detalle. En rojo, los picos.</small></p></div>
<div class="card"><h2 style="margin-top:0">Canales que más se cortan</h2>
<p><small>Ordenados por <b>cortes por hora</b> de visionado (así un canal muy visto no sale primero solo por eso). Solo canales con al menos {MIN_WATCH_FOR_RANKING // 60} min vistos.
<span class="warn">Amarillo</span> ≥ 2/h · <span class="bad">rojo</span> ≥ 6/h.</small></p>
<div class="table-wrap"><table><tr><th>#</th><th>Canal / contenido</th><th>Cortes/h</th><th>Cortes</th><th>Tiempo cargando</th><th>Tiempo visto</th><th>Sesiones</th><th>Arranque medio</th><th>Errores</th></tr>
{ch_table}</table></div></div>
<div class="card"><h2 style="margin-top:0">Dispositivos con más cortes</h2>
<p><small>Si un dispositivo se corta en todos los canales, suele ser su conexión (WiFi, operador), no el canal.</small></p>
<div class="table-wrap"><table><tr><th>Dispositivo</th><th>Cortes/h</th><th>Cortes</th><th>Tiempo visto</th><th>Ubicación</th></tr>{dev_rows}</table></div></div>
<p style="text-align:center"><small>Se guardan los últimos {PLAYS_KEEP_DAYS} días.</small></p>"""))

    # ---- admin: DNS anti-bloqueos
    def page_dns(self, q: dict):
        rng = q.get("r") if q.get("r") in DNS_RANGES else "24h"
        span, label, bucket = DNS_RANGES[rng]
        since = now() - span
        m = dns_metrics()
        cur = dns_snapshot(m) if m is not None else None
        doh = dns_doh_ok() if m is not None else False
        with db() as conn:
            prev = conn.execute("SELECT * FROM dns_samples WHERE ts < ? ORDER BY ts DESC LIMIT 1", (since,)).fetchone()
            rows = conn.execute("SELECT * FROM dns_samples WHERE ts >= ? ORDER BY ts", (since,)).fetchall()
            first = conn.execute("SELECT MIN(ts) t FROM dns_samples").fetchone()["t"]

        # Los contadores de evade-proxy son acumulados y vuelven a 0 si se reinicia: se suman las diferencias
        # entre muestras seguidas (una bajada = reinicio, cuenta el valor nuevo entero).
        start = since - since % bucket
        nb = (now() - start) // bucket + 1
        ev_b, q_b, blk_b = [0] * nb, [0] * nb, [0] * nb
        tot = {"processed": 0, "evaded": 0, "upfail": 0, "dropped": 0}
        last = prev
        for r in rows:
            i = min(nb - 1, max(0, (r["ts"] - start) // bucket))
            for k in tot:
                d = r[k] - last[k] if last is not None and r[k] >= last[k] else (r[k] if last is not None else 0)
                tot[k] += d
                if k == "evaded":
                    ev_b[i] += d
                elif k == "processed":
                    q_b[i] += d
            blk_b[i] = max(blk_b[i], r["blocked_v4"] + r["blocked_v6"])
            last = r

        # Gráfico: barras = bloqueos esquivados por intervalo; línea = IPs bloqueadas (máximo del intervalo).
        W, H, pad = 900, 200, 24
        bw = (W - 2 * pad) / nb
        peak_ev, peak_blk = max(ev_b) or 1, max(blk_b) or 1
        parts, pts = [], []
        for i in range(nb):
            x = pad + i * bw
            h = (H - 44) * ev_b[i] / peak_ev
            t0 = datetime.fromtimestamp(start + i * bucket, TZ)
            tip = (t0.strftime("%d/%m %H:%M") + f" · {fmt_num(ev_b[i])} bloqueos esquivados · {fmt_num(q_b[i])} consultas"
                   f" · {fmt_num(blk_b[i])} IPs bloqueadas")
            parts.append(f'<rect x="{x + 1:.1f}" y="{H - 20 - h:.1f}" width="{max(bw - 2, 1):.1f}" height="{max(h, 0):.1f}" rx="2" '
                         f'fill="#ffb95c"><title>{e(tip)}</title></rect>')
            pts.append(f"{x + bw / 2:.1f},{H - 20 - (H - 44) * blk_b[i] / peak_blk:.1f}")
        if any(blk_b):
            parts.append(f'<polyline points="{" ".join(pts)}" fill="none" stroke="#ff6b6b" stroke-width="2" '
                         f'stroke-linejoin="round" pointer-events="none"/>')
        step = max(1, nb // 8)
        for i in range(0, nb, step):
            t0 = datetime.fromtimestamp(start + i * bucket, TZ)
            fmt = "%H:%M" if span <= 86400 else ("%d/%m %Hh" if span <= 7 * 86400 else "%d/%m")
            parts.append(f'<text x="{pad + i * bw:.1f}" y="{H - 4}" fill="#a49fb8" font-size="11">{t0.strftime(fmt)}</text>')
        parts.append(f'<text x="{pad}" y="12" fill="#ffb95c" font-size="11">máx. {fmt_num(max(ev_b))} esquivados</text>'
                     f'<text x="{W - pad}" y="12" fill="#ff6b6b" font-size="11" text-anchor="end">máx. {fmt_num(max(blk_b))} IPs bloqueadas</text>')
        chart = f'<svg viewBox="0 0 {W} {H}" width="100%" role="img" aria-label="Bloqueos esquivados en el tiempo">{"".join(parts)}</svg>'

        def btn(key, text):
            cls = "btn" if key == rng else "btn ghost"
            return f'<a class="{cls}" href="/admin/dns?r={key}" style="padding:6px 14px">{text}</a>'

        ranges = " ".join(btn(k, v[1]) for k, v in DNS_RANGES.items())
        pct = round(100 * tot["evaded"] / tot["processed"], 2) if tot["processed"] else 0
        fails = tot["upfail"] + tot["dropped"]

        if cur is None:
            estado = ('<span class="pill disabled">Sin respuesta</span> <small>evade-proxy no responde en este VPS. '
                      'Si no está instalado: <span class="code">vps/instalar-dns-evade.sh</span>. Si lo está, en el VPS: '
                      '<span class="code">systemctl status xdp-evade-proxy</span></small>')
            now_tiles = ""
        else:
            doh_pill = '<span class="pill active">DoH activo</span>' if doh else '<span class="pill disabled">DoH sin respuesta</span>'
            cf_warn = "" if cur["cf_ranges"] else ' <span class="bad">· sin rangos de Cloudflare: la evasión no funciona (mira el timer update-blocked-ips)</span>'
            estado = f'<span class="pill active">Activo</span> {doh_pill} <small>{fmt_num(cur["cf_ranges"])} rangos de Cloudflare cargados{cf_warn}</small>'
            now_tiles = f"""<div class="stat"><b class="{'warn' if cur['blocked_v4'] + cur['blocked_v6'] > 50 else ''}">{fmt_num(cur['blocked_v4'] + cur['blocked_v6'])}</b>
<small>IPs bloqueadas ahora por los operadores ({fmt_num(cur['blocked_v4'])} IPv4 · {fmt_num(cur['blocked_v6'])} IPv6)</small></div>
<div class="stat"><b>{fmt_num(cur['evadable'])}</b><small>de ellas se pueden esquivar</small></div>"""

        aviso = ""
        if not rows:
            aviso = ('<p><small>Aún no hay historial en este periodo. El panel guarda una muestra por minuto desde '
                     + (f"el {fmt_time(first)}." if first else "ahora; vuelve en unos minutos.") + "</small></p>")

        return self.html(layout("DNS", f"""<div class="card"><h2 style="margin-top:0">DNS anti-bloqueos</h2><p>{estado}</p>
<p><small>Cuando un operador bloquea una IP de Cloudflare (p. ej. en los partidos), el DNS la cambia por otra que funciona.
La lista de IPs bloqueadas se actualiza sola cada 15 s.</small></p></div>
<div class="stats">{now_tiles}
<div class="stat"><b>{fmt_num(tot['evaded'])}</b><small>bloqueos esquivados · {label} ({pct}% de las consultas)</small></div>
<div class="stat"><b>{fmt_num(tot['processed'])}</b><small>consultas DNS · {label}</small></div>
<div class="stat"><b class="{'bad' if fails else ''}">{fmt_num(fails)}</b><small>consultas fallidas o descartadas · {label}</small></div></div>
<div class="card" style="display:flex;flex-wrap:wrap;gap:6px;align-items:center"><b>Periodo:</b> {ranges}</div>
<div class="card"><h2 style="margin-top:0">Bloqueos esquivados · {label}</h2>{chart}
<div class="legend"><span><span class="dot" style="background:#ffb95c"></span>Bloqueos esquivados</span>
<span><span class="dot" style="background:#ff6b6b"></span>IPs bloqueadas por los operadores</span></div>{aviso}
<p><small>Pasa el ratón por una barra para ver el detalle. Se guardan los últimos {DNS_KEEP_DAYS} días.</small></p></div>"""))

    # ---- admin: mapa
    def map_points(self) -> list:
        with db() as conn:
            devices = conn.execute("SELECT id, code, username, notes, status, expires_at, ip, last_seen, model, manufacturer "
                                   "FROM devices WHERE ip IS NOT NULL").fetchall()
            watching = {r["device_id"]: r for r in live_plays(conn)}
        points = []
        for d in devices:
            g = geo(d["ip"])
            if not g:
                continue
            w = watching.get(d["id"])
            points.append({"id": d["id"], "code": d["code"], "user": d["username"] or "", "notes": d["notes"] or "",
                           "status": effective_status(d), "ip": d["ip"], "seen": fmt_time(d["last_seen"]),
                           "device": f'{d["manufacturer"] or ""} {d["model"] or ""}'.strip(),
                           "place": ", ".join(x for x in (g["city"], g["region"], g["country"]) if x),
                           "lat": g["lat"], "lon": g["lon"],
                           "watching": (w["title"] or "") if w else None,
                           "state": w["state"] if w else None})
        return points

    def page_map(self):
        points = self.map_points()
        if not geo_available():
            warn = ('<div class="card"><p class="err">Falta la base de datos de geolocalización o python3-maxminddb. '
                    'Ejecuta en el VPS el script de actualización del panel.</p></div>')
        else:
            warn = ""
        data = json.dumps(points).replace("</", "<\\/")
        return self.html(layout("Mapa", f"""{warn}<div class="card"><h2 style="margin-top:0">Mapa de clientes</h2>
<p><small>Ubicación aproximada según la IP de la última conexión de cada dispositivo (normalmente la ciudad, a veces la del operador). {len(points)} dispositivo(s) en el mapa. Se actualiza solo cada 30 s.</small></p>
<div id="map"></div>
<div class="legend"><small><span class="dot" style="background:#5cd18a"></span>Viendo ahora</small>
<small><span class="dot" style="background:#ffb95c"></span>Activo</small><small><span class="dot" style="background:#8a86a0"></span>Pendiente / desactivado / caducado</small></div></div>
<script src="/static/leaflet/leaflet.js"></script>
<script>
const STATUS={{active:"Activo",pending:"Pendiente",disabled:"Desactivado",expired:"Caducado"}};
const STATE={{playing:"Reproduciendo",buffering:"Cargando",paused:"En pausa",error:"Error"}};
const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}})[c]);
const map=L.map("map",{{worldCopyJump:true}}).setView([40.4,-3.7],5);
L.tileLayer("https://tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png",{{maxZoom:18,referrerPolicy:"strict-origin-when-cross-origin",
  attribution:'&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> · <a href="https://db-ip.com">IP Geolocation by DB-IP</a>'}}).addTo(map);
const layer=L.layerGroup().addTo(map);let first=true;
function draw(points){{
  layer.clearLayers();const groups={{}};
  for(const p of points){{(groups[p.lat+","+p.lon]??=[]).push(p);}}
  const bounds=[];
  for(const k in groups){{
    const g=groups[k],live=g.some(p=>p.watching!==null),act=g.some(p=>p.status==="active");
    const color=live?"#5cd18a":act?"#ffb95c":"#8a86a0";
    const html="<b>"+esc(g[0].place)+"</b> · "+g.length+" dispositivo(s)<hr style='border:0;border-top:1px solid #ddd'>"+g.map(p=>
      "<a href='/admin/device/"+p.id+"'><b>"+esc(p.code)+"</b></a> "+esc(p.user)+" <small>("+(STATUS[p.status]||p.status)+")</small><br>"+
      (p.watching!==null?"&#9654; "+esc(p.watching)+" <small>"+(STATE[p.state]||"")+"</small><br>":"")+
      "<small>"+esc(p.device)+" · "+esc(p.ip)+" · "+esc(p.seen)+(p.notes?" · "+esc(p.notes):"")+"</small>").join("<br><br>");
    L.circleMarker([g[0].lat,g[0].lon],{{radius:Math.min(7+3*Math.sqrt(g.length),24),color:color,fillColor:color,fillOpacity:.55,weight:2}})
      .bindPopup(html,{{maxHeight:320}}).addTo(layer);
    bounds.push([g[0].lat,g[0].lon]);
  }}
  if(first&&bounds.length){{map.fitBounds(bounds,{{padding:[40,40],maxZoom:10}});first=false;}}
}}
draw({data});
setInterval(()=>fetch("/admin/api/map",{{credentials:"same-origin"}}).then(r=>r.ok?r.json():null).then(j=>j&&draw(j.points)).catch(()=>{{}}),30000);
</script>""", head='<link rel="stylesheet" href="/static/leaflet/leaflet.css">'))

    # ---- admin: login
    def page_login(self, error: str):
        msg = f'<p class="err">{e(error)}</p>' if error else ""
        return self.html(layout("Acceso", f"""<div class="card" style="max-width:420px;margin:60px auto">
<h2 style="margin-top:0">Acceso al panel</h2>{msg}
<form method="post" action="/admin/login"><label>Usuario</label><input name="user" autocomplete="username" autofocus>
<label>Contraseña</label><input name="password" type="password" autocomplete="current-password">
<p><button type="submit">Entrar</button></p></form></div>""", logged=False))

    def do_login(self):
        ip = self.client_ip()
        if throttled(ip):
            return self.redirect("/admin/login?error=" + quote("Demasiados intentos. Espera 15 minutos."))
        f = self.form()
        cfg = load_config()
        if f.get("user") == cfg["admin_user"] and check_password(f.get("password", ""), cfg["admin_hash"]):
            secure = "; Secure" if COOKIE_SECURE else ""
            cookie = f"{SESSION_COOKIE}={make_session(cfg)}; Max-Age={SESSION_TTL}; Path=/; HttpOnly; SameSite=Strict{secure}"
            return self.redirect("/admin", {"Set-Cookie": cookie})
        note_failure(ip)
        return self.redirect("/admin/login?error=" + quote("Usuario o contraseña incorrectos"))

    # ---- admin: lista
    def page_devices(self, q: dict):
        search, status, msg = q.get("q", "").strip(), q.get("status", ""), q.get("msg", "")
        sql, args = "SELECT * FROM devices WHERE 1=1", []
        if search:
            like = f"%{search}%"
            sql += " AND (code LIKE ? OR model LIKE ? OR manufacturer LIKE ? OR notes LIKE ? OR username LIKE ? OR ip LIKE ?)"
            args += [like] * 6
        if status in ("pending", "active", "disabled"):
            sql += " AND status = ?"
            args.append(status)
        sql += " ORDER BY (status='pending') DESC, COALESCE(last_seen, created_at) DESC LIMIT 500"
        with db() as conn:
            rows = conn.execute(sql, args).fetchall()
            counts = {r[0]: r[1] for r in conn.execute("SELECT status, COUNT(*) FROM devices GROUP BY status")}
        trs = []
        for r in rows:
            st = effective_status(r)
            trs.append(f"""<tr><td><a class="code" href="/admin/device/{r['id']}">{e(r['code'])}</a></td>
<td><span class="pill {st}">{STATUS_LABEL[st]}</span></td>
<td>{e(r['manufacturer'])} {e(r['model'])}<br><small>Android {e(r['android'])} · app {e(r['app_version'])}</small></td>
<td>{e(r['username']) or '—'}{((' <span class="pill active" title="Backup activado">backup</span>' if r['backup_enabled'] else ' <span class="pill disabled" title="Backup configurado pero desactivado">backup off</span>') if (r['backup_server_url'] and r['backup_username'] and r['backup_password']) else '')}<br><small>{e(r['notes'])}</small></td>
<td>{fmt_time(r['last_seen'])}<br><small>{e(r['ip'])}{(' · ' + e(geo_label(r['ip']))) if r['ip'] else ''}</small></td>
<td>{fmt_time(r['expires_at'])}</td></tr>""")
        table = "".join(trs) or '<tr><td colspan="6"><small>No hay dispositivos.</small></td></tr>'
        flash = f'<p class="flash">{e(msg)}</p>' if msg else ""
        opts = "".join(f'<option value="{k}"{" selected" if status == k else ""}>{v}</option>'
                       for k, v in [("", "Todos"), ("pending", "Pendientes"), ("active", "Activos"), ("disabled", "Desactivados")])
        return self.html(layout("Dispositivos", f"""{flash}
<div class="card"><form method="get" action="/admin/code" class="row" style="align-items:end">
<div><label>Activar dispositivo: código que aparece en la TV</label><input name="code" class="code" placeholder="ABC123" maxlength="8" autofocus></div>
<div style="flex:0"><button type="submit">Buscar</button></div></form></div>
<div class="card"><form method="get" class="row" style="align-items:end">
<div><label>Buscar (código, modelo, usuario, IP, notas)</label><input name="q" value="{e(search)}"></div>
<div><label>Estado</label><select name="status">{opts}</select></div>
<div style="flex:0"><button class="btn ghost" type="submit">Filtrar</button></div></form>
<p><small>Pendientes: {counts.get('pending', 0)} · Activos: {counts.get('active', 0)} · Desactivados: {counts.get('disabled', 0)}</small></p>
<div class="table-wrap"><table><tr><th>Código</th><th>Estado</th><th>Dispositivo</th><th>Usuario / notas</th><th>Última conexión</th><th>Caduca</th></tr>
{table}</table></div></div>"""))

    def find_code(self, code: str):
        code = code.strip().upper().replace("-", "").replace(" ", "")
        with db() as conn:
            row = conn.execute("SELECT id FROM devices WHERE code = ?", (code,)).fetchone()
        if not row:
            return self.redirect("/admin?msg=" + quote(f"No hay ningún dispositivo con el código {code}"))
        return self.redirect(f"/admin/device/{row['id']}")

    # ---- admin: dispositivo
    def page_device(self, dev_id: str):
        if not dev_id.isdigit():
            return self.redirect("/admin")
        with db() as conn:
            r = conn.execute("SELECT * FROM devices WHERE id = ?", (int(dev_id),)).fetchone()
            default_dns = get_setting(conn, "default_server_url")
        if not r:
            return self.redirect("/admin")
        with db() as conn:
            plays = conn.execute("SELECT * FROM plays WHERE device_id = ? ORDER BY updated_at DESC LIMIT 20", (r["id"],)).fetchall()
        st = effective_status(r)
        own_dns = r["server_url"] or ""
        place = geo_label(r["ip"])
        now_watching = ""
        if plays and plays[0]["state"] != "stopped" and plays[0]["updated_at"] >= now() - LIVE_WINDOW:
            p0 = plays[0]
            now_watching = (f'<p>&#9654; Viendo ahora: <b>{e(p0["title"])}</b> <span class="pill {e(p0["state"])}">'
                            f'{STATE_LABEL.get(p0["state"], "")}</span> · {fmt_dur(p0["watch_s"])} · cortes: {p0["buffering_count"]}</p>')
        prow = "".join(f"""<tr><td>{fmt_time(p['started_at'])}</td><td>{e(p['title'])}</td><td>{fmt_dur(p['watch_s'])}</td>
<td>{p['buffering_count']} <small>({fmt_dur(p['buffering_s'])})</small></td><td>{e(geo_label(p['ip'])) or e(p['ip'])}</td></tr>""" for p in plays)
        history = (f'<div class="card"><h3 style="margin-top:0">Últimas reproducciones</h3><div class="table-wrap"><table>'
                   f'<tr><th>Inicio</th><th>Contenido</th><th>Duración</th><th>Cortes</th><th>Ubicación</th></tr>{prow}</table></div></div>') if plays else ""
        exp = datetime.fromtimestamp(int(r["expires_at"]), TZ).strftime("%Y-%m-%d") if r["expires_at"] else ""
        status_opts = "".join(f'<option value="{k}"{" selected" if r["status"] == k else ""}>{v}</option>'
                              for k, v in [("active", "Activo"), ("pending", "Pendiente"), ("disabled", "Desactivado")])
        return self.html(layout(r["code"], f"""<p><a href="/admin">← Dispositivos</a></p>
<div class="card"><h2 style="margin-top:0">Dispositivo <span class="code">{e(r['code'])}</span> <span class="pill {st}">{STATUS_LABEL[st]}</span></h2>
<p><small>{e(r['manufacturer'])} {e(r['model'])} · Android {e(r['android'])} · app {e(r['app_version'])}<br>
Registrado {fmt_time(r['created_at'])} · Última conexión {fmt_time(r['last_seen'])} desde {e(r['ip'])}{(' (' + e(place) + ')') if place else ''}</small></p>
{now_watching}
<form method="post" action="/admin/device/{r['id']}">
<label>Estado</label><select name="status">{status_opts}</select>
<div class="row"><div><label>Usuario</label><input name="username" value="{e(r['username'])}" required autocomplete="off"></div>
<div><label>Contraseña</label><input name="password" value="{e(r['password'])}" required autocomplete="off"></div></div>
<label>DNS propia de este dispositivo (vacío = DNS global: {e(default_dns) or 'sin configurar'})</label>
<input name="server_url" value="{e(own_dns)}" placeholder="vacío = usar la DNS global">
<h3 style="margin:22px 0 0">Proveedor de backup <small style="font-weight:400">(opcional)</small></h3>
<p style="margin:4px 0 0"><small>Si un canal se corta y no vuelve, la app ofrece canales de nombre parecido de este proveedor.
Sus canales no aparecen nunca en las listas de la app. Déjalo vacío para no usar backup.</small></p>
<label class="check"><input type="checkbox" name="backup_enabled" value="1"{' checked' if r['backup_enabled'] else ''}> Backup activado para este dispositivo</label>
<label>DNS del backup (http://host:puerto)</label><input name="backup_server_url" value="{e(r['backup_server_url'])}" placeholder="vacío = sin backup">
<div class="row"><div><label>Usuario backup</label><input name="backup_username" value="{e(r['backup_username'])}" autocomplete="off"></div>
<div><label>Contraseña backup</label><input name="backup_password" value="{e(r['backup_password'])}" autocomplete="off"></div></div>
<h3 style="margin:22px 0 0">Otros datos</h3>
<div class="row"><div><label>Caduca (vacío = nunca)</label><input name="expires" type="date" value="{exp}"></div>
<div><label>Notas (cliente, teléfono…)</label><input name="notes" value="{e(r['notes'])}"></div></div>
<p><button type="submit">Guardar</button></p></form></div>
{history}<div class="card"><form method="post" action="/admin/device/{r['id']}/delete" onsubmit="return confirm('¿Borrar este dispositivo? La TV mostrará un código nuevo.')">
<button class="btn danger" type="submit">Borrar dispositivo</button></form></div>"""))

    def save_device(self, dev_id: str):
        if not dev_id.isdigit():
            return self.redirect("/admin")
        f = self.form()
        status = f.get("status") if f.get("status") in ("pending", "active", "disabled") else "pending"
        exp_ts = None
        if f.get("expires", "").strip():
            try:
                exp_ts = int(datetime.strptime(f["expires"].strip(), "%Y-%m-%d").replace(hour=23, minute=59, tzinfo=TZ).timestamp())
            except ValueError:
                exp_ts = None
        with _db_lock, db() as conn:
            bk_url = normalize_url(f.get("backup_server_url", ""))
            bk_user = f.get("backup_username", "").strip() if bk_url else ""
            conn.execute("UPDATE devices SET status=?, server_url=?, username=?, password=?, expires_at=?, notes=?,"
                         " backup_username=?, backup_password=?, backup_server_url=?, backup_enabled=? WHERE id=?",
                         (status, normalize_url(f.get("server_url", "")) or None, f.get("username", "").strip(),
                          f.get("password", ""), exp_ts, clip(f.get("notes", "").strip(), 300),
                          bk_user or None, (f.get("backup_password", "") or None) if bk_user else None,
                          bk_url or None, 1 if f.get("backup_enabled") == "1" else 0, int(dev_id)))
        return self.redirect("/admin?msg=" + quote("Dispositivo guardado"))

    def delete_device(self, dev_id: str):
        if dev_id.isdigit():
            with _db_lock, db() as conn:
                conn.execute("DELETE FROM plays WHERE device_id = ?", (int(dev_id),))
                conn.execute("DELETE FROM devices WHERE id = ?", (int(dev_id),))
        return self.redirect("/admin?msg=" + quote("Dispositivo borrado"))

    # ---- admin: ajustes
    def page_settings(self, msg: str):
        with db() as conn:
            dns = get_setting(conn, "default_server_url")
            app_dns_1 = get_setting(conn, "app_dns_1", APP_DNS_DEFAULT[0])
            app_dns_2 = get_setting(conn, "app_dns_2", APP_DNS_DEFAULT[1])
            own = conn.execute("SELECT COUNT(*) FROM devices WHERE server_url IS NOT NULL AND server_url != ''").fetchone()[0]
        flash = f'<p class="flash">{e(msg)}</p>' if msg else ""
        return self.html(layout("Ajustes", f"""{flash}<div class="card"><h2 style="margin-top:0">DNS global</h2>
<p><small>La reciben todos los dispositivos sin DNS propia la próxima vez que se conecten (al abrir la app).
Ahora mismo {own} dispositivo(s) tienen una DNS propia y no se ven afectados.</small></p>
<form method="post" action="/admin/settings"><label>DNS del servidor Xtream (http://host:puerto)</label>
<input name="default_server_url" value="{e(dns)}" required>
<label class="check"><input type="checkbox" name="reset_own" value="1"> Quitar también la DNS propia de todos los dispositivos</label>
<p><button type="submit">Guardar</button></p></form></div>
<div class="card"><h2 style="margin-top:0">DNS de la app (resolución)</h2>
<p><small>Los servidores DNS con los que la app resuelve los nombres (servidor Xtream, logos, guía...).
Cada vez que se abre, la app prueba la principal y, si no responde, usa la secundaria. Si ninguna responde,
usa la DNS normal del aparato. Se aplica a todos los dispositivos la próxima vez que abran la app.</small></p>
<form method="post" action="/admin/settings/app-dns"><label>DNS principal (IP)</label>
<input name="app_dns_1" value="{e(app_dns_1)}" required placeholder="{APP_DNS_DEFAULT[0]}">
<label>DNS secundaria (IP, opcional)</label>
<input name="app_dns_2" value="{e(app_dns_2)}" placeholder="{APP_DNS_DEFAULT[1]}">
<p><button type="submit">Guardar</button></p></form></div>
"""))

    def save_app_dns(self):
        f = self.form()
        first = valid_ip(f.get("app_dns_1", ""))
        raw_second = f.get("app_dns_2", "").strip()
        second = valid_ip(raw_second) if raw_second else ""
        if not first or second is None:
            return self.redirect("/admin/settings?msg=" + quote("Escribe una IP válida (por ejemplo 185.253.152.214)"))
        with _db_lock, db() as conn:
            set_setting(conn, "app_dns_1", first)
            set_setting(conn, "app_dns_2", second)
        return self.redirect("/admin/settings?msg=" + quote("DNS de la app guardadas"))

    def save_settings(self):
        f = self.form()
        dns = normalize_url(f.get("default_server_url", ""))
        if not dns:
            return self.redirect("/admin/settings?msg=" + quote("La DNS no puede estar vacía"))
        with _db_lock, db() as conn:
            set_setting(conn, "default_server_url", dns)
            if f.get("reset_own") == "1":
                conn.execute("UPDATE devices SET server_url = NULL")
        return self.redirect("/admin/settings?msg=" + quote("DNS global guardada"))



def main() -> None:
    if len(sys.argv) >= 2 and sys.argv[1] == "hash":
        # La contraseña llega por stdin, nunca como argumento (se vería en la lista de procesos).
        print(hash_password(sys.stdin.readline().rstrip("\n")))
        return
    if len(sys.argv) == 3 and sys.argv[1] == "set-dns":
        init_db()
        with db() as conn:
            set_setting(conn, "default_server_url", normalize_url(sys.argv[2]))
        print("DNS global:", normalize_url(sys.argv[2]))
        return
    init_db()
    load_config()  # falla pronto y claro si falta la configuración
    threading.Thread(target=dns_sampler, name="dns_sampler", daemon=True).start()
    server = ThreadingHTTPServer(LISTEN, Handler)
    server.daemon_threads = True
    sys.stderr.write(f"8K PRO panel escuchando en {LISTEN[0]}:{LISTEN[1]}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
