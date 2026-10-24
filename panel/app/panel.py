#!/usr/bin/env python3
"""8KPROweb — panel de activación de dispositivos (solo biblioteca estándar de Python 3.12).

Un único proceso con:
  * API para la app:   POST /api/v1/register   GET /api/v1/config
  * Web de administración en /admin (login, lista, activar por código, editar, ajustes)

Escucha en 127.0.0.1:8080 detrás de Nginx, que pone el HTTPS. Datos en SQLite.

Archivos:
  /etc/8kpro-panel/config.json   {"admin_user", "admin_hash", "secret"}   (lo crea el instalador)
  /var/lib/8kpro-panel/panel.db  dispositivos + ajustes (DNS global)

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
from datetime import datetime
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, quote, urlparse

PANEL_VERSION = "1.1"
CONFIG_PATH = os.environ.get("PANEL_CONFIG", "/etc/8kpro-panel/config.json")
DB_PATH = os.environ.get("PANEL_DB", "/var/lib/8kpro-panel/panel.db")
LISTEN = (os.environ.get("PANEL_HOST", "127.0.0.1"), int(os.environ.get("PANEL_PORT", "8080")))
# Detrás de Nginx la cookie va con Secure; en pruebas locales sin TLS se puede desactivar.
COOKIE_SECURE = os.environ.get("PANEL_INSECURE_COOKIE") != "1"

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
    return datetime.fromtimestamp(int(ts)).strftime("%d/%m/%Y %H:%M")


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


def get_setting(conn, key: str, default: str = "") -> str:
    row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else default


def set_setting(conn, key: str, value: str) -> None:
    conn.execute("INSERT INTO settings(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                 (key, value))


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
"""


def layout(title: str, body: str, logged: bool = True) -> str:
    nav = ('<nav><a class="btn ghost" href="/admin">Dispositivos</a><a class="btn ghost" href="/admin/settings">Ajustes</a>'
           '<a class="btn ghost" href="/admin/logout">Salir</a></nav>') if logged else ""
    return (f'<!doctype html><html lang="es"><head><meta charset="utf-8">'
            f'<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">'
            f'<title>{e(title)} · 8K PRO</title><style>{CSS}</style></head><body>'
            f'<header><b>8K <span>PRO</span> · Panel</b>{nav}</header><main>{body}'
            f'<p style="text-align:center"><small>Panel v{PANEL_VERSION}</small></p></main></body></html>')


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
            if path == "/admin/login":
                return self.do_login()
            if not self.is_admin():
                return self.redirect("/admin/login")
            # CSRF: además de SameSite=Strict, exigir que el formulario venga de esta misma web.
            origin = self.headers.get("Origin")
            host = self.headers.get("Host", "")
            # Origin "null" (privacy settings, old pages cached with no-referrer) is let through: the
            # session cookie is SameSite=Strict, so a foreign site cannot send it with a POST anyway.
            origin_host = (urlparse(origin).hostname or "") if origin and origin != "null" else ""
            if origin_host and origin_host.lower() != host.split(":")[0].strip("[]").lower():
                return self.send(403, b"Origen no permitido", "text/plain; charset=utf-8")
            if path == "/admin/settings":
                return self.save_settings()
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
        with _db_lock, db() as conn:
            row = conn.execute("SELECT * FROM devices WHERE device_id = ?", (device_id,)).fetchone()
            if row:
                if not hmac.compare_digest(row["token_hash"], sha256(token)):
                    return self.json(409, {"error": "device_id in use"})
                conn.execute("UPDATE devices SET model=?, manufacturer=?, android=?, app_version=?, ip=?, last_seen=? WHERE id=?",
                             (*info, self.client_ip(), now(), row["id"]))
                return self.json(200, {"code": row["code"]})
            code = new_code(conn)
            conn.execute("INSERT INTO devices (device_id, token_hash, code, model, manufacturer, android, app_version, ip, created_at, last_seen)"
                         " VALUES (?,?,?,?,?,?,?,?,?,?)",
                         (device_id, sha256(token), code, *info, self.client_ip(), now(), now()))
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
            conn.execute("UPDATE devices SET last_seen=?, ip=?, app_version=COALESCE(NULLIF(?, ''), app_version) WHERE id=?",
                         (now(), self.client_ip(), version, row["id"]))
            status = effective_status(row)
            server = row["server_url"] or get_setting(conn, "default_server_url")
            if status == "active" and not (server and row["username"] and row["password"]):
                status = "pending"
            out = {"status": status, "code": row["code"]}
            if status == "active":
                out.update(server_url=server, username=row["username"], password=row["password"])
            return self.json(200, out)

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
<td>{e(r['username']) or '—'}<br><small>{e(r['notes'])}</small></td>
<td>{fmt_time(r['last_seen'])}<br><small>{e(r['ip'])}</small></td>
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
        st = effective_status(r)
        own_dns = r["server_url"] or ""
        exp = datetime.fromtimestamp(int(r["expires_at"])).strftime("%Y-%m-%d") if r["expires_at"] else ""
        status_opts = "".join(f'<option value="{k}"{" selected" if r["status"] == k else ""}>{v}</option>'
                              for k, v in [("active", "Activo"), ("pending", "Pendiente"), ("disabled", "Desactivado")])
        return self.html(layout(r["code"], f"""<p><a href="/admin">← Dispositivos</a></p>
<div class="card"><h2 style="margin-top:0">Dispositivo <span class="code">{e(r['code'])}</span> <span class="pill {st}">{STATUS_LABEL[st]}</span></h2>
<p><small>{e(r['manufacturer'])} {e(r['model'])} · Android {e(r['android'])} · app {e(r['app_version'])}<br>
Registrado {fmt_time(r['created_at'])} · Última conexión {fmt_time(r['last_seen'])} desde {e(r['ip'])}</small></p>
<form method="post" action="/admin/device/{r['id']}">
<label>Estado</label><select name="status">{status_opts}</select>
<div class="row"><div><label>Usuario</label><input name="username" value="{e(r['username'])}" required autocomplete="off"></div>
<div><label>Contraseña</label><input name="password" value="{e(r['password'])}" required autocomplete="off"></div></div>
<label>DNS propia de este dispositivo (vacío = DNS global: {e(default_dns) or 'sin configurar'})</label>
<input name="server_url" value="{e(own_dns)}" placeholder="vacío = usar la DNS global">
<div class="row"><div><label>Caduca (vacío = nunca)</label><input name="expires" type="date" value="{exp}"></div>
<div><label>Notas (cliente, teléfono…)</label><input name="notes" value="{e(r['notes'])}"></div></div>
<p><button type="submit">Guardar</button></p></form></div>
<div class="card"><form method="post" action="/admin/device/{r['id']}/delete" onsubmit="return confirm('¿Borrar este dispositivo? La TV mostrará un código nuevo.')">
<button class="btn danger" type="submit">Borrar dispositivo</button></form></div>"""))

    def save_device(self, dev_id: str):
        if not dev_id.isdigit():
            return self.redirect("/admin")
        f = self.form()
        status = f.get("status") if f.get("status") in ("pending", "active", "disabled") else "pending"
        exp_ts = None
        if f.get("expires", "").strip():
            try:
                exp_ts = int(datetime.strptime(f["expires"].strip(), "%Y-%m-%d").replace(hour=23, minute=59).timestamp())
            except ValueError:
                exp_ts = None
        with _db_lock, db() as conn:
            conn.execute("UPDATE devices SET status=?, server_url=?, username=?, password=?, expires_at=?, notes=? WHERE id=?",
                         (status, normalize_url(f.get("server_url", "")) or None, f.get("username", "").strip(),
                          f.get("password", ""), exp_ts, clip(f.get("notes", "").strip(), 300), int(dev_id)))
        return self.redirect("/admin?msg=" + quote("Dispositivo guardado"))

    def delete_device(self, dev_id: str):
        if dev_id.isdigit():
            with _db_lock, db() as conn:
                conn.execute("DELETE FROM devices WHERE id = ?", (int(dev_id),))
        return self.redirect("/admin?msg=" + quote("Dispositivo borrado"))

    # ---- admin: ajustes
    def page_settings(self, msg: str):
        with db() as conn:
            dns = get_setting(conn, "default_server_url")
            own = conn.execute("SELECT COUNT(*) FROM devices WHERE server_url IS NOT NULL AND server_url != ''").fetchone()[0]
        flash = f'<p class="flash">{e(msg)}</p>' if msg else ""
        return self.html(layout("Ajustes", f"""{flash}<div class="card"><h2 style="margin-top:0">DNS global</h2>
<p><small>La reciben todos los dispositivos sin DNS propia la próxima vez que se conecten (al abrir la app).
Ahora mismo {own} dispositivo(s) tienen una DNS propia y no se ven afectados.</small></p>
<form method="post" action="/admin/settings"><label>DNS del servidor Xtream (http://host:puerto)</label>
<input name="default_server_url" value="{e(dns)}" required>
<label class="check"><input type="checkbox" name="reset_own" value="1"> Quitar también la DNS propia de todos los dispositivos</label>
<p><button type="submit">Guardar</button></p></form></div>"""))

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
    server = ThreadingHTTPServer(LISTEN, Handler)
    server.daemon_threads = True
    sys.stderr.write(f"8K PRO panel escuchando en {LISTEN[0]}:{LISTEN[1]}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
