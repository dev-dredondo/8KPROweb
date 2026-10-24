@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Compilando 8KPROweb
set "ROOT=%~dp0"
set "PROJ=%ROOT%codigo"
set "LOG=%ROOT%compilacion.log"
set "OUT=%ROOT%APK"
echo [%date% %time%] Inicio > "%LOG%"

echo == 1/4 Descomprimiendo proyecto ==
rem Siempre se descomprime: asi se compila la ultima version del codigo, no la de la primera vez.
powershell -NoProfile -Command "Expand-Archive -Force -LiteralPath '%ROOT%8KPROweb_codigo.zip' -DestinationPath '%ROOT%codigo'" >> "%LOG%" 2>&1
if not exist "%PROJ%\gradlew.bat" ( echo ERROR: no se pudo descomprimir >> "%LOG%" & echo ERROR: no se pudo descomprimir & goto :fin )

echo == 2/4 Buscando Java (JBR de Android Studio) ==
set "JH="
for %%D in ("%ProgramFiles%\Android\Android Studio\jbr" "%LOCALAPPDATA%\Programs\Android Studio\jbr" "%ProgramFiles%\Android\Android Studio1\jbr") do (
  if not defined JH if exist "%%~D\bin\java.exe" set "JH=%%~D"
)
if defined JH goto :havejava
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%buscar_java.ps1" > "%TEMP%\8kpro_jh.txt" 2>> "%LOG%"
set /p JH=<"%TEMP%\8kpro_jh.txt"
:havejava
if not defined JH if defined JAVA_HOME set "JH=%JAVA_HOME%"
if not defined JH ( echo ERROR: no encuentro Java/Android Studio >> "%LOG%" & echo ERROR: no encuentro Java & goto :fin )
set "JAVA_HOME=%JH%"
echo JAVA_HOME=%JAVA_HOME% >> "%LOG%"

echo == 3/4 Buscando Android SDK ==
set "SDK=%ANDROID_HOME%"
if not defined SDK set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not exist "%SDK%\platform-tools" if not exist "%SDK%\platforms" ( echo ERROR: no encuentro el Android SDK en %SDK% >> "%LOG%" & echo ERROR: no encuentro el SDK & goto :fin )
set "ANDROID_HOME=%SDK%"
set "SDKF=%SDK:\=/%"
> "%PROJ%\local.properties" echo sdk.dir=%SDKF%
echo ANDROID_HOME=%SDK% >> "%LOG%"
dir /b "%SDK%\platforms" >> "%LOG%" 2>&1

echo == 4/4 Compilando APK release optimizado (10-15 minutos) ==
pushd "%PROJ%"
if not exist "%ROOT%server_url.txt" ( echo ERROR: falta server_url.txt con la DNS >> "%LOG%" & echo ERROR: falta server_url.txt con la DNS & goto :fin )
set /p SERVERURL=<"%ROOT%server_url.txt"
echo DNS: leida de server_url.txt >> "%LOG%"
set "PANELARG="
if exist "%ROOT%panel_url.txt" if exist "%ROOT%panel_pin.txt" (
  set /p PANELURL=<"%ROOT%panel_url.txt"
  set /p PANELPIN=<"%ROOT%panel_pin.txt"
  set "PANELARG="-Pbrand.panelUrl=!PANELURL!" "-Pbrand.panelPin=!PANELPIN!""
  echo Panel de activacion: configurado >> "%LOG%"
) else (
  echo AVISO: sin panel_url.txt/panel_pin.txt, solo login manual >> "%LOG%"
)
rem ---- Firma de la version release: clave propia, creada una sola vez y guardada en "firma\".
rem      GUARDA UNA COPIA de la carpeta "firma": sin ella no podras publicar actualizaciones.
set "FIRMA=%ROOT%firma"
if not exist "%FIRMA%" mkdir "%FIRMA%"
if not exist "%FIRMA%\8kpro.keystore" (
  powershell -NoProfile -Command "[guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')" > "%FIRMA%\clave.txt"
  set /p SIGNPASS=<"%FIRMA%\clave.txt"
  "%JAVA_HOME%\bin\keytool.exe" -genkeypair -noprompt -keystore "%FIRMA%\8kpro.keystore" -storetype PKCS12 -alias 8kpro -keyalg RSA -keysize 2048 -validity 10000 -storepass "!SIGNPASS!" -keypass "!SIGNPASS!" -dname "CN=8K PRO, O=8K PRO, C=ES" >> "%LOG%" 2>&1
  echo Clave de firma creada en firma\8kpro.keystore >> "%LOG%"
)
if not exist "%FIRMA%\8kpro.keystore" ( echo ERROR: no se pudo crear la clave de firma >> "%LOG%" & echo ERROR: no se pudo crear la clave de firma & goto :fin )
set /p SIGNPASS=<"%FIRMA%\clave.txt"
set "KEYSTORE_FILE=%FIRMA%\8kpro.keystore"
set "KEYSTORE_PASSWORD=!SIGNPASS!"
set "KEY_ALIAS=8kpro"
set "KEY_PASSWORD=!SIGNPASS!"
set "TMDBARG="
if exist "%ROOT%tmdb_key.txt" (
  set /p TMDBKEY=<"%ROOT%tmdb_key.txt"
  set "TMDBARG=-Pbrand.tmdbApiKey=!TMDBKEY!"
  echo Clave TMDB: encontrada >> "%LOG%"
) else (
  echo AVISO: sin tmdb_key.txt, los metadatos TMDB no funcionaran >> "%LOG%"
)
call gradlew.bat :app:assembleStandardRelease "-Pbrand.abis=armeabi-v7a,arm64-v8a,x86_64" "-Pbrand.serverUrl=!SERVERURL!" !TMDBARG! !PANELARG! --stacktrace >> "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
popd
echo GRADLE_EXIT=%RC% >> "%LOG%"
if not "%RC%"=="0" ( echo ERROR: la compilacion fallo, ver compilacion.log & goto :fin )

if not exist "%OUT%" mkdir "%OUT%"
copy /y "%PROJ%\app\build\outputs\apk\standard\release\app-standard-release.apk" "%OUT%\8KPROweb.apk" >> "%LOG%" 2>&1
echo APK_OK >> "%LOG%"
echo.
echo LISTO: %OUT%\8KPROweb.apk
:fin
echo [%date% %time%] Fin >> "%LOG%"
echo.
echo Puedes cerrar esta ventana.
pause
