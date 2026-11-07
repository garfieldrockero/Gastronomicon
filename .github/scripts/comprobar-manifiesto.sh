#!/usr/bin/env bash
# Comprueba el manifiesto final y la configuración de red de la APK.
# Se ejecuta tanto en la APK de PR como en la firmada de main. Sin secretos.
set -euo pipefail

manifiesto="${1:-}"
if [ -z "$manifiesto" ]; then
  manifiesto=$(find app/build/intermediates -path '*merged_manifest*' -path '*elease*' -name AndroidManifest.xml | head -n 1)
fi
if [ -z "$manifiesto" ]; then
  echo "::error::No se encuentra el manifiesto final del release."
  exit 1
fi
echo "Manifiesto final: $manifiesto"

# Se juntan las líneas porque el manifiesto combinado puede partir un elemento.
permisos=$(tr '\n' ' ' < "$manifiesto" | grep -oE '<uses-permission[^>]*>' | sed -E 's/.*android:name="([^"]+)".*/\1/' | sort -u || true)
echo "Permisos declarados:"
if [ -z "$permisos" ]; then
  echo "  (ninguno)"
else
  echo "$permisos" | sed 's/^/  /'
fi

# Solo estos permisos están aprobados. DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
# la añade AndroidX Core: es un permiso signature de la propia app para sus
# receptores no exportados y no da acceso a nada externo.
permitidos="android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE io.github.garfieldrockero.gastronomicon.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
while IFS= read -r permiso; do
  [ -z "$permiso" ] && continue
  case " $permitidos " in
    *" $permiso "*) ;;
    *)
      echo "::error::Permiso no aprobado en el manifiesto: $permiso"
      exit 1
      ;;
  esac
done <<< "$permisos"

if grep -qE "android:usesCleartextTraffic=['\"]true['\"]" "$manifiesto"; then
  echo "::error::El manifiesto activa el tráfico HTTP en claro para toda la app."
  exit 1
fi

if ! grep -qE "android:networkSecurityConfig=['\"]@xml/seguridad_red['\"]" "$manifiesto"; then
  echo "::error::Falta android:networkSecurityConfig=\"@xml/seguridad_red\" en el manifiesto."
  exit 1
fi

if grep -qE "android:debuggable=['\"]true['\"]" "$manifiesto"; then
  echo "::error::El manifiesto de release permite depurar la app."
  exit 1
fi

# allowBackup=false no basta en Android 12+: la transferencia directa a otro
# móvil se controla con dataExtractionRules, y esta app no debe exportar nada.
if ! grep -qE "android:allowBackup=['\"]false['\"]" "$manifiesto"; then
  echo "::error::El manifiesto no desactiva las copias de seguridad."
  exit 1
fi
if ! grep -qE "android:dataExtractionRules=['\"]@xml/extraccion_datos['\"]" "$manifiesto"; then
  echo "::error::Falta android:dataExtractionRules=\"@xml/extraccion_datos\" en el manifiesto."
  exit 1
fi

# Solo el launcher y el receptor de perfil de AndroidX pueden estar exportados.
componentes=$(tr '\n' ' ' < "$manifiesto" | grep -oE '<(activity|activity-alias|service|receiver|provider)[^>]*>' || true)
while IFS= read -r componente; do
  [ -z "$componente" ] && continue
  if printf '%s' "$componente" | grep -qE "android:exported=['\"]true['\"]"; then
    nombre=$(printf '%s' "$componente" | sed -nE 's/.*android:name="([^"]+)".*/\1/p')
    case "$nombre" in
      ".MainActivity"|"io.github.garfieldrockero.gastronomicon.MainActivity"|"androidx.profileinstaller.ProfileInstallReceiver") ;;
      *)
        echo "::error::Componente exportado no aprobado: ${nombre:-desconocido}"
        exit 1
        ;;
    esac
  fi
done <<< "$componentes"

# Lista blanca exacta, no lista negra: si alguien cambia la configuración de
# red (una CA propia, un pin-set, HTTP en claro), el archivo deja de coincidir.
red=app/src/main/res/xml/seguridad_red.xml
esperado=.github/scripts/seguridad_red.esperado.xml
if [ ! -f "$red" ] || [ ! -f "$esperado" ]; then
  echo "::error::Falta $red o $esperado."
  exit 1
fi
if ! cmp -s "$red" "$esperado"; then
  echo "::error::seguridad_red.xml no coincide con la configuración aprobada."
  exit 1
fi

extraccion=app/src/main/res/xml/extraccion_datos.xml
if [ ! -f "$extraccion" ]; then
  echo "::error::No se encuentra $extraccion."
  exit 1
fi

echo "Manifiesto correcto: permisos aprobados, sin tráfico en claro, sin depuración, sin copias, sin componentes exportados de más y con la configuración de red aprobada."
