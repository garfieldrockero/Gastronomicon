#!/usr/bin/env bash
# Verifica la firma de la APK final y muestra la huella SHA-256 de su certificado.
# La huella es pública (no es un secreto): sirve para comprobar que main firma
# siempre con la misma clave, porque si cambia Android no deja actualizar la app.
# La variable de repositorio FIRMA_HUELLA_SHA256 es obligatoria y llega en
# HUELLA_ESPERADA: si falta, se falla en vez de seguir en verde. Sin secretos.
# Uso: HUELLA_ESPERADA=… comprobar-firma.sh [ruta/a/la.apk]
set -euo pipefail

# shellcheck source=herramientas-ci.sh
. "$(dirname "${BASH_SOURCE[0]}")/herramientas-ci.sh"

apk=$(localizar_apk "${1:-}")
apksigner=$(herramienta_sdk apksigner)
echo "APK firmada: $apk"
echo "apksigner: $apksigner"

# Formato esperado de «apksigner verify --print-certs» (una línea por firmante):
#   Signer #1 certificate DN: CN=…
#   Signer #1 certificate SHA-256 digest: 0a1b2c…   (64 hex, minúsculas, sin «:»)
# Si la firma no es válida, apksigner sale con error y no se llega a la huella.
salida=$("$apksigner" verify --print-certs "$apk") || {
  echo "::error::apksigner no da por válida la firma de $apk."
  exit 1
}

# Se ignoran los espacios y los retornos de carro y se pasa a minúsculas.
huellas=$(grep -E '^Signer #[0-9]+ certificate SHA-256 digest:' <<< "$salida" \
  | sed -E 's/^.*digest:[[:space:]]*//' | tr -d ' \r' | tr 'A-F' 'a-f' | sort -u || true)
cuantas=$(grep -c . <<< "$huellas" || true)
if [ "$cuantas" -ne 1 ]; then
  echo "::error::Se esperaba una única huella SHA-256 en la salida de apksigner y hay $cuantas. Salida recibida:"
  volcar_seguro <<< "$salida"
  exit 1
fi
huella="$huellas"
if ! [[ "$huella" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::La huella leída de apksigner no tiene 64 dígitos hexadecimales: $huella"
  exit 1
fi

echo "Huella SHA-256 del certificado de firma: $huella"

# La clave de depuración de Android no sirve nunca para main: es la que Gradle usa
# si falta la clave fija. Se rechaza antes de mirar la huella esperada.
if grep -qE '^Signer #[0-9]+ certificate DN:.*CN=Android Debug' <<< "$salida"; then
  echo "::error::La APK está firmada con la clave de depuración de Android (CN=Android Debug), no con la clave fija."
  exit 1
fi
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Huella de la firma"
    echo
    echo "SHA-256 del certificado de la APK firmada (pública): \`$huella\`"
  } >> "$GITHUB_STEP_SUMMARY"
fi

# Se admiten mayúsculas y separadores «:» (el formato de keytool).
# La huella esperada es obligatoria: si falta, no se comprueba nada y no se puede
# dar por buena la firma. Antes se salía con 0, que dejaba la comprobación en
# silencio; ahora es un fallo claro (el paso solo se ejecuta en main).
esperada=$(printf '%s' "${HUELLA_ESPERADA:-}" | tr -d ': \t\r\n' | tr 'A-F' 'a-f')
if [ -z "$esperada" ]; then
  echo "::error::Falta la variable de repositorio FIRMA_HUELLA_SHA256: sin ella no se comprueba que la clave sea la de siempre, y una clave distinta impediría actualizar la app instalada sin desinstalarla. Sácala en local con «keytool -list -v» del keystore que guardas; no la copies de una ejecución de CI, porque podría fijar una clave equivocada."
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "**Falta \`FIRMA_HUELLA_SHA256\`:** sin ella no se comprueba la clave. Calcula la huella en local con \`keytool -list -v\` sobre tu keystore y defínela como variable de repositorio; no la copies de esta ejecución." >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 1
fi
if ! [[ "$esperada" =~ ^[0-9a-f]{64}$ ]]; then
  echo "::error::La variable FIRMA_HUELLA_SHA256 no es una huella SHA-256 válida (64 dígitos hexadecimales, con o sin «:»)."
  exit 1
fi
if [ "$huella" != "$esperada" ]; then
  echo "::error::La APK está firmada con otra clave. Esperada (FIRMA_HUELLA_SHA256): $esperada. Obtenida: $huella. Si se publica, Android no dejará actualizar la app instalada sin desinstalarla."
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "**La huella no coincide con \`FIRMA_HUELLA_SHA256\`** (esperada \`$esperada\`)." >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 1
fi
echo "La huella coincide con FIRMA_HUELLA_SHA256."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "Coincide con \`FIRMA_HUELLA_SHA256\`." >> "$GITHUB_STEP_SUMMARY"
fi
