#!/usr/bin/env bash
# Comprueba la APK real (no el manifiesto intermedio de Gradle): permisos,
# manifiesto final y configuración de red. Se ejecuta tanto en la APK de PR como
# en la firmada de main. Sin secretos.
# Uso: comprobar-manifiesto.sh [ruta/a/la.apk]
set -euo pipefail

# shellcheck source=herramientas-ci.sh
. "$(dirname "${BASH_SOURCE[0]}")/herramientas-ci.sh"

apk=$(localizar_apk "${1:-}")
aapt2=$(herramienta_sdk aapt2)
echo "APK comprobada: $apk"
echo "aapt2: $aapt2"

# --- Permisos ---------------------------------------------------------------
# Formato esperado de «aapt2 dump permissions», una línea por permiso:
#   uses-permission: name='android.permission.INTERNET'
# En versiones antiguas puede salir sin «name=» (uses-permission: android.permission.X)
# y con extras detrás (maxSdkVersion='23'); el parseo admite las dos formas. También
# salen líneas «permission:» con los permisos que DECLARA la app (no los que pide):
# esas no se mezclan con las «uses-permission». Se cuentan como peticiones todas las
# variantes (uses-permission-sdk-23, uses-implied-permission…).
salida_permisos=$("$aapt2" dump permissions "$apk") || {
  echo "::error::aapt2 no pudo leer los permisos de $apk."
  exit 1
}

lineas_permisos=$(grep -E '^[[:space:]]*uses-(implied-)?permission[A-Za-z0-9-]*:' <<< "$salida_permisos" || true)
permisos=""
while IFS= read -r linea; do
  [ -z "$linea" ] && continue
  nombre=$(sed -nE "s/^[^'\"]*name=['\"]([^'\"]+)['\"].*/\1/p" <<< "$linea")
  if [ -z "$nombre" ]; then
    # Sin «name=»: el nombre es la primera palabra tras los dos puntos.
    nombre=$(sed -E 's/^[^:]*:[[:space:]]*//; s/[[:space:]].*$//' <<< "$linea")
  fi
  permisos+="$nombre"$'\n'
done <<< "$lineas_permisos"
permisos=$(printf '%s' "$permisos" | sort -u)

echo "Permisos pedidos:"
if [ -z "$permisos" ]; then
  echo "  (ninguno)"
else
  sed 's/^/  /' <<< "$permisos" | volcar_seguro
fi
declarados=$(sed -nE 's/^[[:space:]]*permission:[[:space:]]*(name=)?['"'"'"]?([^'"'"'" ]+).*/\2/p' <<< "$salida_permisos" | sort -u)
if [ -n "$declarados" ]; then
  echo "Permisos que declara la app (solo informativo):"
  sed 's/^/  /' <<< "$declarados" | volcar_seguro
fi

# Si falta INTERNET es casi seguro que el formato de aapt2 ha cambiado y el
# parseo no ha visto nada: mejor fallar que dar por buena una lista vacía.
case $'\n'"$permisos"$'\n' in
  *$'\nandroid.permission.INTERNET\n'*) ;;
  *)
    echo "::error::No se ha leído el permiso INTERNET en la salida de aapt2; el formato puede haber cambiado. Salida recibida:"
    volcar_seguro <<< "$salida_permisos"
    exit 1
    ;;
esac

# Solo estos permisos están aprobados. DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
# la añade AndroidX Core: es un permiso signature de la propia app para sus
# receptores no exportados y no da acceso a nada externo.
# POST_NOTIFICATIONS se pide en tiempo de ejecución al activar el recordatorio y
# RECEIVE_BOOT_COMPLETED solo deja reprogramar su alarma. Cualquier otro permiso
# (SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, WAKE_LOCK…) rompe el CI: la lista falla
# cerrada.
permitidos="android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.POST_NOTIFICATIONS android.permission.RECEIVE_BOOT_COMPLETED io.github.garfieldrockero.gastronomicon.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
while IFS= read -r permiso; do
  [ -z "$permiso" ] && continue
  case " $permitidos " in
    *" $permiso "*) ;;
    *)
      echo "::error::Permiso no aprobado en la APK: $permiso"
      exit 1
      ;;
  esac
done <<< "$permisos"

# --- Manifiesto final -------------------------------------------------------
# «aapt2 dump xmltree --file AndroidManifest.xml» imprime el XML binario así:
#   E: application (line=12)
#     A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
#     A: http://schemas.android.com/apk/res/android:exported(0x01010010)=(type 0x12)0xffffffff
#     A: http://schemas.android.com/apk/res/android:name(0x01010003)="x.Y" (Raw: "x.Y")
#     A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f0f0001
# Los booleanos salen como «true»/«false» o como «(type 0x12)0xffffffff» (true) y
# «(type 0x12)0x0» (false), y las referencias como @0x7f…, no como @xml/nombre. Se
# normaliza todo a un registro por elemento, «elemento<TAB>clave=valor<TAB>…», con la
# clave sin espacio de nombres ni id, las cadenas sin comillas ni «(Raw: …)» y los
# booleanos como true/false. Lo que no se reconoce se deja tal cual.
salida_xml=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk") || {
  echo "::error::aapt2 no pudo leer AndroidManifest.xml de $apk."
  exit 1
}

registros=$(awk '
  function vaciar(   i, linea) {
    if (elemento != "") {
      linea = elemento
      for (i = 1; i <= n; i++) linea = linea "\t" claves[i] "=" valores[i]
      print linea
    }
    elemento = ""
    n = 0
  }
  {
    texto = $0
    sub(/^[ \t]+/, "", texto)
    if (texto ~ /^E: /) {
      vaciar()
      elemento = substr(texto, 4)
      sub(/[ \t].*$/, "", elemento)
    } else if (texto ~ /^A: / && elemento != "") {
      resto = substr(texto, 4)
      p = index(resto, "=")
      if (p == 0) next
      clave = substr(resto, 1, p - 1)
      valor = substr(resto, p + 1)
      sub(/\(0x[0-9a-fA-F]+\)$/, "", clave)
      sub("^.*[:/]", "", clave)
      sub(/ \(Raw: .*$/, "", valor)
      if (valor ~ /^\(type 0x12\)/) {
        sub(/^\(type 0x12\)/, "", valor)
        valor = (valor ~ /^(0x)?0+$/) ? "false" : "true"
      } else if (substr(valor, 1, 1) == "\"") {
        sub(/^"/, "", valor)
        sub(/"$/, "", valor)
      }
      gsub(/\t/, " ", valor)
      n++
      claves[n] = clave
      valores[n] = valor
    }
  }
  END { vaciar() }
' <<< "$salida_xml")

if ! grep -qE $'^manifest(\t|$)' <<< "$registros" || ! grep -qE $'^application(\t|$)' <<< "$registros"; then
  echo "::error::No se han podido leer los elementos manifest y application de la APK; el formato de aapt2 puede haber cambiado. Salida recibida:"
  volcar_seguro <<< "$salida_xml"
  exit 1
fi

# Valor del atributo $2 del primer elemento $1 (vacío si no existe).
valor_de() {
  awk -F'\t' -v e="$1" -v k="$2" '
    $1 == e { for (i = 2; i <= NF; i++) if (index($i, k "=") == 1) { print substr($i, length(k) + 2); exit } }
  ' <<< "$registros"
}
# Éxito si el primer elemento $1 tiene el atributo $2, aunque esté vacío.
tiene_atributo() {
  awk -F'\t' -v e="$1" -v k="$2" '
    BEGIN { r = 1 }
    $1 == e { for (i = 2; i <= NF; i++) if (index($i, k "=") == 1) r = 0; exit }
    END { exit r }
  ' <<< "$registros"
}

# El HTTP en claro hacia la red local lo permite solo la network security config
# revisada (comparada más abajo), nunca el manifiesto: aquí no puede haber
# usesCleartextTraffic. Se pide «false» explícito, no solo «distinto de true»: una
# referencia a un recurso (@0x7f…) no se puede evaluar aquí y tampoco debe pasar.
if tiene_atributo application usesCleartextTraffic && [ "$(valor_de application usesCleartextTraffic)" != "false" ]; then
  echo "::error::El manifiesto activa o deja sin determinar el tráfico HTTP en claro para toda la app desde el manifiesto (usesCleartextTraffic=$(valor_de application usesCleartextTraffic))."
  exit 1
fi

# Basta con que exista aquí: más abajo se resuelve, con «aapt2 dump resources», a
# qué recurso de la APK apunta y se compara su contenido compilado con una lista
# blanca. La comparación de ficheros del repositorio no demuestra qué se empaquetó.
if ! tiene_atributo application networkSecurityConfig; then
  echo "::error::Falta android:networkSecurityConfig en el manifiesto de la APK."
  exit 1
fi

if tiene_atributo application debuggable && [ "$(valor_de application debuggable)" != "false" ]; then
  echo "::error::La APK de release permite depurar la app (debuggable=$(valor_de application debuggable))."
  exit 1
fi

# allowBackup=false no basta en Android 12+: la transferencia directa a otro
# móvil se controla con dataExtractionRules, y esta app no debe exportar nada.
if [ "$(valor_de application allowBackup)" != "false" ]; then
  echo "::error::El manifiesto no desactiva las copias de seguridad (allowBackup=false)."
  exit 1
fi
# Igual que con la red: aquí basta con que exista; más abajo se lee el recurso
# compilado al que apunta y se compara con una lista blanca.
if ! tiene_atributo application dataExtractionRules; then
  echo "::error::Falta android:dataExtractionRules en el manifiesto de la APK."
  exit 1
fi

# Solo el launcher y el receptor de perfil de AndroidX pueden estar exportados.
# Un «exported» que no sea false (true o una referencia) cuenta como exportado.
exportados=$(awk -F'\t' '
  $1 ~ /^(activity|activity-alias|service|receiver|provider)$/ {
    exportado = ""
    nombre = "(sin nombre)"
    for (i = 2; i <= NF; i++) {
      if (index($i, "exported=") == 1) exportado = substr($i, 10)
      if (index($i, "name=") == 1) nombre = substr($i, 6)
    }
    if (exportado != "" && exportado != "false") print nombre
  }
' <<< "$registros")
while IFS= read -r nombre; do
  [ -z "$nombre" ] && continue
  case "$nombre" in
    ".MainActivity"|"io.github.garfieldrockero.gastronomicon.MainActivity"|"androidx.profileinstaller.ProfileInstallReceiver") ;;
    *)
      echo "::error::Componente exportado no aprobado: $nombre"
      exit 1
      ;;
  esac
done <<< "$exportados"

# Que el launcher aparezca exportado demuestra que el parseo ve los componentes: sin
# esto, un cambio de formato de aapt2 dejaría la lista de exportados vacía y "correcta".
launcher=$(awk -F'\t' '
  $1 == "activity" {
    exportado = ""
    nombre = ""
    for (i = 2; i <= NF; i++) {
      if (index($i, "exported=") == 1) exportado = substr($i, 10)
      if (index($i, "name=") == 1) nombre = substr($i, 6)
    }
    if (exportado == "true" && nombre == "io.github.garfieldrockero.gastronomicon.MainActivity") print "si"
  }
' <<< "$registros")
if [ "$launcher" != "si" ]; then
  echo "::error::El parseo no ha visto el launcher (MainActivity con exported=true) en el manifiesto de la APK."
  exit 1
fi

# --- FileProvider de la cámara ----------------------------------------------
# El único provider de la app es el FileProvider con el que la cámara del sistema deja la
# foto en nuestra caché. Lista blanca exacta (clase, autoridad, no exportado y permiso
# temporal por Uri): un provider añadido por una librería o a mano rompe el CI aunque no
# pida permisos. La autoridad se deriva de `${applicationId}` en el manifiesto, así que se
# compara con el nombre de paquete fijo de la app.
proveedores=$(awk -F'\t' '
  $1 == "provider" {
    nombre = "(sin nombre)"
    autoridades = ""
    exportado = ""
    concesion = ""
    for (i = 2; i <= NF; i++) {
      if (index($i, "name=") == 1) nombre = substr($i, 6)
      if (index($i, "authorities=") == 1) autoridades = substr($i, 13)
      if (index($i, "exported=") == 1) exportado = substr($i, 10)
      if (index($i, "grantUriPermissions=") == 1) concesion = substr($i, 21)
    }
    print nombre "\t" autoridades "\t" exportado "\t" concesion
  }
' <<< "$registros")
# `profileinstaller` (ya aprobado por su ProfileInstallReceiver) arrastra el provider de
# `androidx.startup`, que no se exporta y no concede Uri. Se admite solo esa línea exacta
# (clase, autoridad y exported=false); cualquier variante rompe el CI.
inicio_esperado=$'androidx.startup.InitializationProvider\tio.github.garfieldrockero.gastronomicon.androidx-startup\tfalse\t'
proveedores=$(grep -Fxv -- "$inicio_esperado" <<< "$proveedores" || true)
proveedor_esperado=$'androidx.core.content.FileProvider\tio.github.garfieldrockero.gastronomicon.camara\tfalse\ttrue'
if [ "$proveedores" != "$proveedor_esperado" ]; then
  echo "::error::El provider del manifiesto de la APK no es el FileProvider aprobado (androidx.core.content.FileProvider, autoridad <applicationId>.camara, exported=false, grantUriPermissions=true)."
  {
    echo "Esperado:"
    printf '%s\n' "$proveedor_esperado"
    echo "Encontrado:"
    if [ -z "$proveedores" ]; then
      echo "  (ninguno)"
    else
      printf '%s\n' "$proveedores"
    fi
  } | volcar_seguro
  exit 1
fi

# Los permisos del manifiesto deben coincidir con los de «dump permissions»: dos
# lecturas independientes de la misma APK, para que una no tape un fallo de la otra.
permisos_xml=$(awk -F'\t' '
  $1 ~ /^uses-permission/ {
    for (i = 2; i <= NF; i++) if (index($i, "name=") == 1) print substr($i, 6)
  }
' <<< "$registros" | sort -u)
if [ "$permisos_xml" != "$permisos" ]; then
  echo "::error::Los permisos de «aapt2 dump permissions» y los del manifiesto (xmltree) no coinciden."
  {
    echo "Según dump permissions:"
    sed 's/^/  /' <<< "$permisos"
    echo "Según xmltree:"
    sed 's/^/  /' <<< "$permisos_xml"
  } | volcar_seguro
  exit 1
fi

# --- Recursos compilados en la APK ------------------------------------------
# Las comparaciones de más abajo son entre dos ficheros del repositorio: eso no
# demuestra que lo empaquetado sea eso. Un AAR con su propio xml, un res.srcDir
# añadido mañana o una variante de configuración podrían poner otra cosa en la APK
# sin tocar esas rutas. Aquí se leen los recursos REALES del artefacto: se resuelve
# a qué xml apunta cada atributo del manifiesto y se compara su árbol compilado con
# una lista blanca exacta.
#
# Formatos que se asumen de las build-tools fijadas en buildToolsVersion. Si
# cambian, el parseo no encontrará lo esperado y el paso falla; no se adivina nada:
#  - «aapt2 dump resources», una entrada por recurso y una línea por configuración:
#      type xml id=0x0f entryCount=1
#        resource 0x7f0f0000 xml/seguridad_red
#          () (file) res/xml/seguridad_red.xml type=XML
#    Una variante (xml-v31…) sale como otra configuración de la misma entrada u
#    otra entrada con el tipo «xml-v31». Se exige: una única entrada para ese
#    nombre, tipo exactamente «xml», el id al que apunta el manifiesto y una sola
#    configuración, la vacía «()».
#  - «aapt2 dump xmltree», dos espacios por nivel:
#      E: base-config (line=12)
#        A: cleartextTrafficPermitted(0x0101000e)=(type 0x12)0xffffffff
#    Los booleanos salen como «true»/«false» o como «1»/«0» tras «(type 0x12)»
#    (0xffffffff es true y 0x0 es false); las cadenas van entre comillas, a veces
#    con «(Raw: …)». Se normaliza a un registro por elemento
#    («ruta/del/elemento<TAB>atributo=valor…»), con la ruta reconstruida por el
#    margen y los atributos ordenados. Cualquier línea que no sea una declaración
#    de espacio de nombres («N:»), un elemento («E:») o un atributo («A:») se
#    convierte en un registro «?…» que no está en la lista blanca: un formato
#    desconocido falla en vez de colarse.
salida_recursos=$("$aapt2" dump resources "$apk") || {
  echo "::error::aapt2 no pudo leer los recursos de $apk."
  exit 1
}

normalizar_xmltree() {
  awk '
    function valor(v) {
      sub(/ \(Raw: .*$/, "", v)
      if (v ~ /^\(type 0x12\)/) {
        sub(/^\(type 0x12\)/, "", v)
        return (v ~ /^(0x)?0+$/) ? "false" : "true"
      }
      if (substr(v, 1, 1) == "\"") { sub(/^"/, "", v); sub(/"$/, "", v) }
      gsub(/\t/, " ", v)
      return v
    }
    function volcar(   i, j, tmp, linea) {
      if (elemento == "") return
      # Orden de atributos fijo: el volcado no tiene por qué traerlos ordenados.
      for (i = 2; i <= n; i++) {
        tmp = atributos[i]
        for (j = i - 1; j >= 1 && atributos[j] > tmp; j--) atributos[j + 1] = atributos[j]
        atributos[j + 1] = tmp
      }
      linea = elemento
      for (i = 1; i <= n; i++) linea = linea "\t" atributos[i]
      print linea
      elemento = ""
      n = 0
    }
    {
      texto = $0
      sub(/\r$/, "", texto)
      if (texto ~ /^[ \t]*$/) next
      if (texto ~ /^[ \t]*N: /) next
      match(texto, /^[ \t]*/)
      sangria = RLENGTH
      cuerpo = texto
      sub(/^[ \t]*/, "", cuerpo)
      if (cuerpo ~ /^E: /) {
        volcar()
        nombre = substr(cuerpo, 4)
        sub(/[ \t(].*$/, "", nombre)
        if (nombre == "") { print "?elemento-sin-nombre"; next }
        while (cima > 0 && pila_sangria[cima] >= sangria) cima--
        padre = (cima > 0) ? pila_ruta[cima] : ""
        elemento = (padre == "") ? nombre : padre "/" nombre
        cima++
        pila_sangria[cima] = sangria
        pila_ruta[cima] = elemento
      } else if (cuerpo ~ /^A: /) {
        if (elemento == "") { print "?atributo-suelto"; next }
        atributo = substr(cuerpo, 4)
        p = index(atributo, "=")
        if (p == 0) { print "?atributo-sin-valor"; next }
        clave = substr(atributo, 1, p - 1)
        sub(/\(0x[0-9a-fA-F]+\)$/, "", clave)
        sub("^.*[:/]", "", clave)
        gsub(/[ \t]/, "", clave)
        if (clave == "") { print "?atributo-sin-nombre"; next }
        n++
        atributos[n] = clave "=" valor(substr(atributo, p + 1))
      } else {
        print "?linea-no-reconocida " cuerpo
      }
    }
    END { volcar() }
  '
}

# --- Receptor del recordatorio ----------------------------------------------
# El manifiesto se vuelve a leer como árbol con rutas (normalizar_xmltree) para
# comprobar los receptores. Lista blanca exacta:
#  - receptores: el de AndroidX de profileinstaller (exportado, como ya estaba
#    aprobado) y el recordatorio de la app, **no exportado**;
#  - acciones del receptor del recordatorio: las cuatro de reprogramación
#    (reinicio, actualización de la APK, cambio de hora y de zona), ni una más;
#  - ningún <service> declarado.
# El receptor va exportado=false y su acción de alarma no está en el filtro: viaja
# en el PendingIntent explícito, así que no es una puerta que se pueda abrir desde
# fuera. Un receptor añadido a mano o una acción de más rompen el CI.
arbol_manifiesto=$(normalizar_xmltree <<< "$salida_xml")

receptores=$(awk -F'\t' '
  $1 == "manifest/application/receiver" {
    nombre = "(sin nombre)"
    exportado = ""
    for (i = 2; i <= NF; i++) {
      if (index($i, "name=") == 1) nombre = substr($i, 6)
      if (index($i, "exported=") == 1) exportado = substr($i, 10)
    }
    print nombre "\t" exportado
  }
' <<< "$arbol_manifiesto" | LC_ALL=C sort)

receptores_esperados=$(
  printf '%s\n' \
    $'androidx.profileinstaller.ProfileInstallReceiver\ttrue' \
    $'io.github.garfieldrockero.gastronomicon.platform.ReminderReceiver\tfalse' |
    LC_ALL=C sort
)
if [ "$receptores" != "$receptores_esperados" ]; then
  echo "::error::Los receptores del manifiesto de la APK no son los aprobados (profileinstaller exportado y el recordatorio de la app no exportado)."
  {
    echo "Esperado:"
    printf '%s\n' "$receptores_esperados"
    echo "Encontrado:"
    if [ -z "$receptores" ]; then
      echo "  (ninguno)"
    else
      printf '%s\n' "$receptores"
    fi
  } | volcar_seguro
  exit 1
fi

# Las acciones se atribuyen a su receptor con el volcado aplanado: cada elemento se
# imprime justo antes del siguiente, así que las acciones de un <receiver> quedan
# detrás de su línea y antes del siguiente componente. Se exige que el receptor del
# recordatorio tenga EXACTAMENTE las cuatro acciones de reprogramación (una acción de
# más rompe el CI). Las de AndroidX (profileinstaller) no se tocan aquí.
acciones_recordatorio=$(awk -F'\t' '
  $1 ~ /^(manifest|application|activity|activity-alias|service|provider|receiver)$/ {
    actual = ""
    if ($1 == "receiver") {
      for (i = 2; i <= NF; i++) if (index($i, "name=") == 1) actual = substr($i, 6)
    }
    next
  }
  $1 == "action" && actual == "io.github.garfieldrockero.gastronomicon.platform.ReminderReceiver" {
    for (i = 2; i <= NF; i++) if (index($i, "name=") == 1) print substr($i, 6)
  }
' <<< "$registros" | LC_ALL=C sort)

acciones_recordatorio_esperadas=$(
  printf '%s\n' \
    "android.intent.action.BOOT_COMPLETED" \
    "android.intent.action.MY_PACKAGE_REPLACED" \
    "android.intent.action.TIME_SET" \
    "android.intent.action.TIMEZONE_CHANGED" |
    LC_ALL=C sort
)
if [ "$acciones_recordatorio" != "$acciones_recordatorio_esperadas" ]; then
  echo "::error::El receptor del recordatorio no tiene exactamente las cuatro acciones aprobadas (reinicio, actualización, hora y zona)."
  {
    echo "Esperado:"
    printf '%s\n' "$acciones_recordatorio_esperadas"
    echo "Encontrado:"
    if [ -z "$acciones_recordatorio" ]; then
      echo "  (ninguna)"
    else
      printf '%s\n' "$acciones_recordatorio"
    fi
  } | volcar_seguro
  exit 1
fi

servicios=$(awk -F'\t' '$1 == "manifest/application/service" { print $1 }' <<< "$arbol_manifiesto")
if [ -n "$servicios" ]; then
  echo "::error::La APK declara un <service>; la app no tiene ninguno aprobado (el recordatorio no usa servicios)."
  volcar_seguro <<< "$servicios"
  exit 1
fi

# Una línea de la lista blanca: la ruta del elemento y, detrás, sus atributos.
arbol_esperado() {
  local ruta=$1
  shift
  printf '%s' "$ruta"
  if [ "$#" -gt 0 ]; then
    printf '\t%s' "$@"
  fi
  printf '\n'
}

# Ruta interna (res/xml/…) del recurso $2 («tipo/nombre») al que apunta la
# referencia $1 («@0x7f…» o «@xml/nombre»). Falla cerrado si la referencia no se
# entiende, si el recurso no aparece, si hay variantes o si el formato de aapt2 no
# es el esperado. Lo único que imprime por la salida estándar es la ruta.
ruta_recurso_apk() {
  local referencia=$1 esperado=$2 buscado id entradas filtro cuantas
  local entrada id_entrada tipo_entrada nombre variantes configuraciones marcador ruta
  case "$referencia" in
    @0x[0-9a-fA-F]*)
      id=$(printf '%s' "${referencia#@}" | tr 'A-F' 'a-f')
      buscado=""
      ;;
    @xml/*)
      # Si el volcado diera la forma simbólica, se busca por nombre.
      id=""
      buscado=${referencia#@}
      ;;
    *)
      echo "::error::«$referencia» no es una referencia a un recurso; no se puede resolver en la APK." >&2
      return 1
      ;;
  esac

  entradas=$(sed -nE 's/^[[:space:]]*resource[[:space:]]+(0[xX][0-9a-fA-F]+)[[:space:]]+([^[:space:]]+).*$/\1\t\2/p' <<< "$salida_recursos")
  if [ -z "$entradas" ]; then
    echo "::error::No se ha reconocido ninguna entrada «resource 0x… tipo/nombre» en «aapt2 dump resources»; el formato puede haber cambiado. Salida recibida:" >&2
    volcar_seguro <<< "$salida_recursos" >&2
    return 1
  fi

  if [ -n "$id" ]; then
    filtro=$(awk -F'\t' -v id="$id" 'tolower($1) == id' <<< "$entradas")
    cuantas=$(grep -c . <<< "$filtro" || true)
    if [ "$cuantas" -ne 1 ]; then
      echo "::error::El manifiesto apunta al recurso $id y «aapt2 dump resources» no tiene exactamente una entrada con ese id ($cuantas)." >&2
      volcar_seguro <<< "$entradas" >&2
      return 1
    fi
  else
    filtro=$(awk -F'\t' -v n="$buscado" '$2 == n' <<< "$entradas")
    cuantas=$(grep -c . <<< "$filtro" || true)
    if [ "$cuantas" -ne 1 ]; then
      echo "::error::La referencia «$referencia» no tiene exactamente una entrada «$buscado» en «aapt2 dump resources» ($cuantas)." >&2
      volcar_seguro <<< "$entradas" >&2
      return 1
    fi
  fi
  entrada=$filtro
  # El id de la entrada, en minúsculas, para localizar su bloque de configuraciones.
  id_entrada=$(printf '%s' "${entrada%%$'\t'*}" | tr 'A-F' 'a-f')
  tipo_entrada=${entrada#*$'\t'}

  # Sin variantes: una única entrada para ese nombre. Una «xml-v31/seguridad_red»
  # aparecería aquí como otra entrada (el tipo lo delata).
  nombre=${esperado#*/}
  variantes=$(awk -F'\t' -v n="$nombre" '{ p = $2; sub(/^.*\//, "", p); if (p == n) print }' <<< "$entradas")
  cuantas=$(grep -c . <<< "$variantes" || true)
  if [ "$cuantas" -ne 1 ]; then
    echo "::error::Hay $cuantas recursos llamados «$nombre» en la APK; se admite una sola configuración, sin variantes (xml-v31…)." >&2
    volcar_seguro <<< "$variantes" >&2
    return 1
  fi
  if [ "$tipo_entrada" != "$esperado" ]; then
    echo "::error::El recurso «$nombre» está empaquetado como «$tipo_entrada» y no como «$esperado» (variante de configuración no admitida)." >&2
    return 1
  fi

  # Líneas de configuración de esa entrada: de su cabecera a la siguiente.
  configuraciones=$(awk -v id="$id_entrada" '
    /^[ \t]*resource[ \t]+/ {
      linea = $0
      sub(/^[ \t]*resource[ \t]+/, "", linea)
      split(linea, campos, /[ \t]+/)
      dentro = (tolower(campos[1]) == id)
      next
    }
    dentro { print }
  ' <<< "$salida_recursos")
  cuantas=$(grep -cE '^[[:space:]]*\([^)]*\)[[:space:]]+\(file\)' <<< "$configuraciones" || true)
  if [ "$cuantas" -ne 1 ]; then
    echo "::error::El recurso «$esperado» tiene $cuantas ficheros de configuración en la APK; se exige una sola, la de por defecto." >&2
    volcar_seguro <<< "$configuraciones" >&2
    return 1
  fi
  marcador=$(sed -nE 's/^[[:space:]]*\(([^)]*)\).*$/\1/p' <<< "$configuraciones")
  if [ -n "$marcador" ]; then
    echo "::error::El recurso «$esperado» tiene una configuración alternativa («$marcador»); se exige la de por defecto." >&2
    return 1
  fi
  ruta=$(sed -nE 's/^[[:space:]]*\([^)]*\)[[:space:]]+\(file\)[[:space:]]+([^[:space:]]+).*$/\1/p' <<< "$configuraciones")
  case "$ruta" in
    res/*.xml) ;;
    *)
      echo "::error::No se ha podido leer la ruta interna del recurso «$esperado» en la APK («$ruta»)." >&2
      return 1
      ;;
  esac
  printf '%s\n' "$ruta"
}

# Compara el árbol compilado del recurso con nombre $2, al que apunta la referencia
# $1, con la lista blanca $3. $4 es cómo se llama en los mensajes (el atributo o el
# elemento del que sale la referencia).
comprobar_recurso_compilado() {
  local referencia=$1 recurso=$2 esperado=$3 etiqueta=$4 ruta salida_arbol arbol
  ruta=$(ruta_recurso_apk "$referencia" "$recurso") || return 1
  echo "Recurso empaquetado para $etiqueta: $ruta"
  if ! salida_arbol=$("$aapt2" dump xmltree --file "$ruta" "$apk"); then
    echo "::error::aapt2 no pudo leer $ruta de $apk." >&2
    return 1
  fi
  arbol=$(normalizar_xmltree <<< "$salida_arbol")
  if [ "$arbol" != "$esperado" ]; then
    echo "::error::El recurso $recurso empaquetado no coincide con la lista blanca esperada ($etiqueta)." >&2
    {
      echo "Esperado:"
      printf '%s\n' "$esperado"
      echo "Encontrado:"
      printf '%s\n' "$arbol"
      echo "Salida de aapt2:"
      volcar_seguro <<< "$salida_arbol"
    } >&2
    return 1
  fi
}

# Lista blanca de la configuración de red: una sola base-config que permite HTTP
# en claro (la frontera real la pone core/), trust-anchors con los certificados del
# sistema y nada más. Un domain-config, un pin-set, un debug-overrides, un
# src="user" o un atributo de más cambian el árbol y fallan.
arbol_red=$(
  arbol_esperado network-security-config
  arbol_esperado network-security-config/base-config cleartextTrafficPermitted=true
  arbol_esperado network-security-config/base-config/trust-anchors
  arbol_esperado network-security-config/base-config/trust-anchors/certificates src=system
)
comprobar_recurso_compilado \
  "$(valor_de application networkSecurityConfig)" xml/seguridad_red "$arbol_red" networkSecurityConfig || exit 1

# Lista blanca de las reglas de extracción: solo «exclude» con path «.» y todos
# los dominios, tanto en cloud-backup como en device-transfer. Un «include», un
# dominio de menos o un path distinto cambian el árbol y fallan.
arbol_extraccion=$(
  arbol_esperado data-extraction-rules
  for bloque in cloud-backup device-transfer; do
    arbol_esperado "data-extraction-rules/$bloque"
    for dominio in root file database sharedpref external device_root device_file device_database device_sharedpref; do
      arbol_esperado "data-extraction-rules/$bloque/exclude" "domain=$dominio" "path=."
    done
  done
)
comprobar_recurso_compilado \
  "$(valor_de application dataExtractionRules)" xml/extraccion_datos "$arbol_extraccion" dataExtractionRules || exit 1

# El FileProvider comparte solo `cacheDir/camara/`. Se exige que su meta-data apunte a un
# único recurso `xml/rutas_camara` y que el árbol compilado sea exactamente un
# `cache-path` llamado `camara` con la ruta `camara/`. Un `files-path`, un `root-path` o
# un `external-path` añadido mañana (por una librería o por error) rompen el CI.
rutas_ref=$(awk -F'\t' '
  $1 == "meta-data" {
    nombre = ""
    recurso = ""
    for (i = 2; i <= NF; i++) {
      if (index($i, "name=") == 1) nombre = substr($i, 6)
      if (index($i, "resource=") == 1) recurso = substr($i, 10)
    }
    if (nombre == "android.support.FILE_PROVIDER_PATHS" && recurso != "") print recurso
  }
' <<< "$registros")
cuantas=$(grep -c . <<< "$rutas_ref" || true)
if [ "$cuantas" -ne 1 ]; then
  echo "::error::Se espera un único meta-data android.support.FILE_PROVIDER_PATHS con su recurso en la APK, y hay $cuantas."
  volcar_seguro <<< "$rutas_ref"
  exit 1
fi
arbol_rutas=$(
  arbol_esperado paths
  arbol_esperado paths/cache-path "name=camara" "path=camara/"
)
comprobar_recurso_compilado \
  "$rutas_ref" xml/rutas_camara "$arbol_rutas" "el FileProvider (rutas_camara)" || exit 1

# --- Recursos del repositorio -----------------------------------------------
# Lista blanca exacta, no lista negra: si alguien cambia la configuración de
# red (una CA propia, un pin-set, cambiar el HTTP en claro), el archivo deja de
# coincidir. El HTTP en claro está permitido a propósito en esa configuración; la
# frontera (solo red local y con interruptor) la impone core/ServerUrl.
# Esto compara las FUENTES; lo empaquetado ya se ha comprobado arriba con aapt2.
# Las dos comprobaciones se mantienen: la de fuentes detecta el cambio antes de
# compilar y la del artefacto demuestra qué salió en la APK.
red=app/src/main/res/xml/seguridad_red.xml
# Ninguna otra network security config en ningún source set o flavor (src/release,
# src/debug…): al fusionar recursos, otra con el mismo nombre taparía la aprobada y
# la comparación de abajo daría por buena la equivocada.
otras=$(grep -rlF 'network-security-config' --include='*.xml' app/src 2>/dev/null | sort | grep -vxF "$red" || true)
if [ -n "$otras" ]; then
  echo "::error::Hay otra network security config además de $red; solo se admite esa:"
  volcar_seguro <<< "$otras"
  exit 1
fi
esperado=.github/scripts/seguridad_red.esperado.xml
if [ ! -f "$red" ] || [ ! -f "$esperado" ]; then
  echo "::error::Falta $red o $esperado."
  exit 1
fi
if ! cmp -s "$red" "$esperado"; then
  echo "::error::seguridad_red.xml no coincide con la configuración aprobada."
  exit 1
fi

# Igual con las reglas de extracción: que exclusión de todo siga siendo exclusión de
# todo. Cambiarlas exige cambiar también la copia aprobada, a propósito.
extraccion=app/src/main/res/xml/extraccion_datos.xml
extraccion_esperada=.github/scripts/extraccion_datos.esperado.xml
if [ ! -f "$extraccion" ] || [ ! -f "$extraccion_esperada" ]; then
  echo "::error::Falta $extraccion o $extraccion_esperada."
  exit 1
fi
if ! cmp -s "$extraccion" "$extraccion_esperada"; then
  echo "::error::extraccion_datos.xml no coincide con la configuración aprobada."
  exit 1
fi

# Y las rutas del FileProvider de la cámara: el árbol compilado ya se ha comparado con la
# lista blanca; aquí se exige además que la fuente del repositorio sea idéntica a la copia
# aprobada, para que ampliar lo que se comparte exija tocar las dos a propósito.
rutas=app/src/main/res/xml/rutas_camara.xml
rutas_esperadas=.github/scripts/rutas_camara.esperado.xml
if [ ! -f "$rutas" ] || [ ! -f "$rutas_esperadas" ]; then
  echo "::error::Falta $rutas o $rutas_esperadas."
  exit 1
fi
if ! cmp -s "$rutas" "$rutas_esperadas"; then
  echo "::error::rutas_camara.xml no coincide con las rutas aprobadas del FileProvider."
  exit 1
fi

echo "APK correcta: permisos aprobados (INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS y RECEIVE_BOOT_COMPLETED); manifiesto sin tráfico en claro global (el HTTP local lo regula la configuración de red aprobada y core/ServerUrl), sin depuración, sin copias y sin componentes exportados de más; un único provider (FileProvider de la cámara, no exportado y con permiso temporal por Uri); receptores y sus acciones con lista blanca exacta (el recordatorio no exportado); ningún service; configuración de red, reglas de extracción y rutas del FileProvider idénticas a las aprobadas, tanto en los ficheros del repositorio como en el recurso compilado de la APK (lista blanca, sin variantes de configuración)."
