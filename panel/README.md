# Panel de activación 8K PRO

Panel web + API para activar la app 8KPROweb por código de dispositivo.
Solo usa Python 3 de serie (sin dependencias), Nginx (HTTPS con certificado propio para la IP) y SQLite.

## Instalar / actualizar (Ubuntu 24.04)

```
curl -fsSL https://raw.githubusercontent.com/dev-dredondo/8KPROweb/main/panel/instalar_panel.sh | sudo DNS=http://TU-DNS-XTREAM bash
```

Volver a ejecutarlo actualiza el panel y conserva administrador, dispositivos, DNS y certificado.

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
| `/var/lib/8kpro-panel/panel.db` | dispositivos y ajustes (haz copia de este archivo) |

Logs: `journalctl -u 8kpro-panel -f`. Reiniciar: `systemctl restart 8kpro-panel`.

Cambiar la contraseña del administrador: borra `/etc/8kpro-panel/config.json` y vuelve a ejecutar el instalador.
