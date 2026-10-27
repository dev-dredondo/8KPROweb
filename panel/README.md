# Panel de activación 8K PRO

Panel web + API para activar la app 8KPROweb por código de dispositivo.
Solo usa Python 3 de serie (sin dependencias), Nginx (HTTPS con certificado propio para la IP) y SQLite.

## Instalar / actualizar (Ubuntu 24.04)

```
curl -fsSL https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel/instalar_panel.sh | sudo DNS=http://TU-DNS-XTREAM bash
```

Con dominio propio (registro A apuntando al VPS) añade `DOMAIN=panel.tudominio.com`: el panel usa un
certificado de Let's Encrypt que se renueva solo **conservando la misma clave** (`--reuse-key`), así la
huella que lleva la app no cambia. No renueves nunca ese certificado a mano sin `--reuse-key`.

Volver a ejecutarlo actualiza el panel y conserva administrador, dispositivos, DNS y certificado.

## Actualizar un panel ya instalado (v1.3: «En directo» y «Mapa»)

```
curl -fsSL "https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel/actualizar_panel.sh?v=$(date +%s)" | sudo bash
```

Instala `python3-maxminddb`, descarga el último `panel.py`, Leaflet (mapa) y la base de datos de
geolocalización **DB-IP City Lite** (CC BY 4.0, se actualiza sola cada mes con `8kpro-geo-update.timer`),
y reinicia el panel.

## Estadísticas y mapa

- La app (8KPROweb ≥ esta versión) envía `POST /api/v1/stats` al empezar cada canal/película, al
  empezar a cargar (buffering) y cada 30 s: qué ve, estado, tiempo viendo, nº de cortes y tiempo
  cargando, tiempo de arranque y resolución. Nunca la URL del stream.
- **En directo**: quién está viendo qué ahora mismo y las últimas 60 sesiones (se guardan 7 días).
- **Estadísticas** (v1.4): cortes por hora, gráfico en el tiempo, canales y dispositivos que más se cortan; periodo de 1 h a 7 días y filtro directo/VOD.
- **Proveedor de backup** (v1.5, Ajustes y ficha de cada dispositivo): DNS, usuario y contraseña de un segundo proveedor.
  Cuando un canal se cae, la app (8KPROmovil ≥ 1.0.3) ofrece los canales de nombre parecido de ese proveedor; nunca aparecen en las listas.
- **Mapa**: cada dispositivo según la IP de su última conexión. Es aproximado (ciudad o, a veces,
  la ubicación del operador). Mapa de © OpenStreetMap, geolocalización de [DB-IP](https://db-ip.com).

## Funcionamiento

1. La app, sin activar, se registra (`POST /api/v1/register`) con un id y un token propios y muestra un código de 6 caracteres.
2. En `https://IP/admin` buscas el código, pones usuario/contraseña Xtream (y DNS propia si hace falta) y lo activas.
3. La app consulta `GET /api/v1/config` (cabeceras `X-Device-Id`, `X-Device-Token`) y entra sola.
4. **Ajustes → DNS global**: la DNS que reciben todos los dispositivos sin DNS propia.

## Archivos en el servidor

| Ruta | Qué |
|---|---|
| `/opt/8kpro-panel/panel.py` | el panel |
| `/etc/8kpro-panel/config.json` | administrador (hash scrypt) y secreto de sesión |
| `/etc/8kpro-panel/tls/` | certificado y clave |
| `/var/lib/8kpro-panel/panel.db` | dispositivos, ajustes y sesiones (haz copia de este archivo) |
| `/var/lib/8kpro-panel/geo/` | base de datos de geolocalización por IP |
| `/opt/8kpro-panel/static/leaflet/` | librería del mapa |

Logs: `journalctl -u 8kpro-panel -f`. Reiniciar: `systemctl restart 8kpro-panel`.

Cambiar la contraseña del administrador: borra `/etc/8kpro-panel/config.json` y vuelve a ejecutar el instalador.
