# firetv-core

Copia del código de OwnTV_Core 1.0.69 (GPL-3.0, https://github.com/ahXN00/OwnTV_Core) usada solo por la
versión de 8KPROweb para Fire TV antiguos (`-Pbrand.firetv=true`, ver `settings.gradle.kts`).

Cambios respecto al original:
- `minSdk` 25 (Android 7.1 / Fire OS 6) en `core` y `player-core`, con *core library desugaring*.
- Lint limitado a `NewApi` (informes `lint-newapi-*.txt`) y sin el control de textos de `tools/i18n`.
- Las llamadas que Android 7.1 no tiene, protegidas con comprobaciones de versión.
