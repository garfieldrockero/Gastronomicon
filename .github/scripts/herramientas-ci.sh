#!/usr/bin/env bash
# Funciones compartidas por los scripts de CI: localizar la APK final y las
# herramientas del SDK del runner. Se carga con «source», no se ejecuta.
# Los avisos y errores van a stderr para que «$(funcion)» solo capture la ruta.

# Devuelve la ruta de la APK final del release. Si se pasa una ruta, la usa tal cual.
# Si no, busca en app/build/outputs/apk/release/ sin contar las «-unsigned.apk»:
# tiene que haber exactamente una, para no comprobar una y publicar otra.
localizar_apk() {
  local indicada="${1:-}" candidata
  local -a apks=()
  if [ -n "$indicada" ]; then
    if [ ! -f "$indicada" ]; then
      echo "::error::No existe la APK indicada: $indicada" >&2
      return 1
    fi
    printf '%s\n' "$indicada"
    return 0
  fi
  for candidata in app/build/outputs/apk/release/*.apk; do
    [ -e "$candidata" ] || continue
    case "$candidata" in
      *-unsigned.apk) continue ;;
    esac
    apks+=("$candidata")
  done
  if [ "${#apks[@]}" -eq 0 ]; then
    echo "::error::No hay ninguna APK firmada en app/build/outputs/apk/release/ (las *-unsigned.apk no cuentan)." >&2
    return 1
  fi
  if [ "${#apks[@]}" -gt 1 ]; then
    echo "::error::Hay más de una APK en app/build/outputs/apk/release/: ${apks[*]}" >&2
    return 1
  fi
  printf '%s\n' "${apks[0]}"
}

# Versión de build-tools que fija el proyecto: se lee de app/build.gradle.kts
# (buildToolsVersion), el único sitio donde se fija. Falla si no hay exactamente
# una asignación, para no quedarse con una comentada o con la primera de dos.
version_build_tools() {
  local gradle version
  gradle="$(dirname "${BASH_SOURCE[0]}")/../../app/build.gradle.kts"
  local lineas
  lineas=$(grep -c 'buildToolsVersion' "$gradle" || true)
  version=$(sed -nE 's/^[[:space:]]*buildToolsVersion[[:space:]]*=[[:space:]]*"([0-9]+(\.[0-9]+){2})"[[:space:]]*$/\1/p' "$gradle")
  if [ -z "$version" ] || [ "$(printf '%s\n' "$version" | wc -l)" -ne 1 ] || [ "$lineas" -ne 1 ]; then
    echo "::error::Se espera exactamente una línea buildToolsVersion = \"X.Y.Z\" en app/build.gradle.kts (sin otras menciones, ni comentadas)." >&2
    return 1
  fi
  printf '%s\n' "$version"
}

# Devuelve la ruta de una herramienta de build-tools (aapt2, apksigner…) en la
# versión fijada en buildToolsVersion, para que la comprobación sea reproducible.
# No se coge «la más alta» del runner: cambiaría con la imagen. No se afirma que
# AGP use estos binarios: puede bajar su propio aapt2 y firmar con su biblioteca.
herramienta_sdk() {
  local nombre="$1" sdk version ruta
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ]; then
    echo "::error::ANDROID_HOME no está definido: no se encuentra el SDK de Android del runner." >&2
    return 1
  fi
  version=$(version_build_tools) || return 1
  ruta="$sdk/build-tools/$version/$nombre"
  if [ ! -x "$ruta" ]; then
    echo "::error::No se encuentra $nombre ejecutable en $sdk/build-tools/$version/ (versión fijada en app/build.gradle.kts)." >&2
    return 1
  fi
  printf '%s\n' "$ruta"
}

# Imprime la entrada estándar sin que el runner interprete como comandos de workflow
# (::error::, ::add-mask::…) las cadenas que vengan dentro de una APK ajena. El
# testigo es aleatorio para que el contenido no pueda cerrar el bloque.
volcar_seguro() {
  local tok
  tok=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
  echo "::stop-commands::$tok"
  cat
  echo "::$tok::"
}
