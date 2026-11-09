#!/usr/bin/env bash
# Exige que las pruebas de las fronteras de seguridad del núcleo (HTTP local, token,
# sesión y saneado de HTML) SE HAYAN EJECUTADO y pasado, no
# solo que existan los archivos: lee los informes JUnit XML que deja «gradle :core:test»
# y, para cada clase de la lista, pide que el informe exista, tests > 0, failures = 0,
# errors = 0 y skipped = 0 (un @Ignore o una suposición fallida cuentan como skipped y
# hacen fallar). Si no puede leer un atributo del <testsuite>, también falla.
# Uso: comprobar-pruebas-frontera.sh [directorio-de-informes]
#   (por defecto core/build/test-results/test)
set -euo pipefail

# shellcheck source=herramientas-ci.sh
. "$(dirname "${BASH_SOURCE[0]}")/herramientas-ci.sh"

directorio="${1:-core/build/test-results/test}"
clases=(
  io.github.garfieldrockero.gastronomicon.core.LocalNetworkTest
  io.github.garfieldrockero.gastronomicon.core.ServerUrlTest
  io.github.garfieldrockero.gastronomicon.core.SecretRedactionTest
  io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransportTest
  io.github.garfieldrockero.gastronomicon.core.config.ServerAddressTest
  io.github.garfieldrockero.gastronomicon.core.config.ApiTokenTest
  io.github.garfieldrockero.gastronomicon.core.network.HttpRequestTest
  io.github.garfieldrockero.gastronomicon.core.network.ConnectionProblemTest
  io.github.garfieldrockero.gastronomicon.core.network.NetworkKindTest
  io.github.garfieldrockero.gastronomicon.core.network.NetworkErrorTest
  io.github.garfieldrockero.gastronomicon.core.network.HttpRequestQueryTest
  io.github.garfieldrockero.gastronomicon.core.network.CoreJsonTest
  io.github.garfieldrockero.gastronomicon.core.recipes.RecipeListQueryTest
  io.github.garfieldrockero.gastronomicon.core.client.MealieClientTest
  io.github.garfieldrockero.gastronomicon.core.client.MealieClientImageTest
  io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailViewTest
  io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDetailViewExtrasTest
  io.github.garfieldrockero.gastronomicon.core.recipes.RecipeSlugTest
  io.github.garfieldrockero.gastronomicon.core.recipes.RecipeImageTest
  io.github.garfieldrockero.gastronomicon.core.html.SafeUrlTest
  io.github.garfieldrockero.gastronomicon.core.config.ServerAddressSplitTest
  io.github.garfieldrockero.gastronomicon.core.session.SaveSessionPolicyTest
  io.github.garfieldrockero.gastronomicon.core.html.HtmlSanitizerTest
  io.github.garfieldrockero.gastronomicon.core.html.PlainTextSanitizerTest
  io.github.garfieldrockero.gastronomicon.core.session.SessionTest
  io.github.garfieldrockero.gastronomicon.core.session.SessionPayloadFormatTest
  io.github.garfieldrockero.gastronomicon.core.offline.ServerScopeTest
  io.github.garfieldrockero.gastronomicon.core.offline.OfflineCodecTest
  io.github.garfieldrockero.gastronomicon.core.offline.RecipeCacheTest
  io.github.garfieldrockero.gastronomicon.core.offline.DraftStoreTest
  io.github.garfieldrockero.gastronomicon.core.offline.OfflineWipeTest
  io.github.garfieldrockero.gastronomicon.core.network.MultipartBodyTest
  io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransportWriteTest
  io.github.garfieldrockero.gastronomicon.core.network.UrlConnectionTransportUploadCutoffTest
  io.github.garfieldrockero.gastronomicon.core.recipes.RecipeDraftValidatorTest
  io.github.garfieldrockero.gastronomicon.core.recipes.ImageMetadataTest
  io.github.garfieldrockero.gastronomicon.core.recipes.ImageRecodePlanTest
  io.github.garfieldrockero.gastronomicon.core.client.RecipeDraftMergeTest
  io.github.garfieldrockero.gastronomicon.core.client.RecipeDraftIngredientMergeTest
  io.github.garfieldrockero.gastronomicon.core.client.MealieClientWriteTest
  io.github.garfieldrockero.gastronomicon.core.client.MealieClientImportUploadTest
  io.github.garfieldrockero.gastronomicon.core.client.MealieClientMaybeCreatedTest
  io.github.garfieldrockero.gastronomicon.core.client.MealieClientCatalogTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingClientTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListControllerTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListPickerControllerTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingListViewTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingTextTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingRawJsonTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingRecipeAddTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingRecipeAdditionsTest
  io.github.garfieldrockero.gastronomicon.core.shopping.ShoppingRecipeControllerTest
  io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanClientTest
  io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanControllerTest
  io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanDraftTest
  io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanWeekViewTest
  io.github.garfieldrockero.gastronomicon.core.mealplan.MealPlanRawJsonTest
)

# Valor numérico del atributo $1 en la etiqueta de apertura $2 (comillas dobles o
# simples, cualquier orden). Vacío si falta o no es un entero. Se exige un espacio antes
# del nombre para que «tests» no coincida con «skipped», «errors» con otro sufijo, etc.
atributo() {
  sed -nE "s/.*[[:space:]]$1[[:space:]]*=[[:space:]]*[\"']([0-9]+)[\"'].*/\1/p" <<< "$2" | head -n 1
}

fallos=0
for clase in "${clases[@]}"; do
  informe="$directorio/TEST-$clase.xml"
  if [ ! -f "$informe" ]; then
    echo "::error::No hay informe de pruebas de $clase ($informe): esta prueba de seguridad del núcleo no se ha ejecutado."
    fallos=1
    continue
  fi

  # La etiqueta puede ocupar varias líneas: se junta el archivo en una y se toma la
  # primera apertura de <testsuite (con espacio detrás, para no confundirla con
  # <testsuites>).
  etiqueta=$(tr '\n\r' '  ' < "$informe" | grep -oE '<testsuite[[:space:]][^>]*>' | head -n 1 || true)
  if [ -z "$etiqueta" ]; then
    echo "::error::No se ha podido leer el <testsuite> de $informe. Contenido (primeras líneas):"
    head -n 20 "$informe" | volcar_seguro
    fallos=1
    continue
  fi

  tests=$(atributo tests "$etiqueta")
  failures=$(atributo failures "$etiqueta")
  errors=$(atributo errors "$etiqueta")
  skipped=$(atributo skipped "$etiqueta")
  if [ -z "$tests" ] || [ -z "$failures" ] || [ -z "$errors" ] || [ -z "$skipped" ]; then
    echo "::error::No se han podido leer tests/failures/errors/skipped del <testsuite> de $informe. Etiqueta recibida:"
    volcar_seguro <<< "$etiqueta"
    fallos=1
    continue
  fi

  # Los contadores no deben contradecir a los <testcase> reales del archivo.
  # «|| true» dentro del grupo: con 0 <testcase> grep -o sale 1 y pipefail + set -e
  # abortarían el script antes de emitir el ::error:: (el recuento 0 se trata abajo).
  casos=$( { tr '\n\r' '  ' < "$informe" | grep -oE '<testcase[[:space:]]' || true; } | wc -l | tr -d ' ')
  echo "$clase: tests=$tests failures=$failures errors=$errors skipped=$skipped (testcase=$casos)"

  # 10# evita que «08» se lea como octal.
  if [ "$((10#$tests))" -le 0 ]; then
    echo "::error::$clase no ha ejecutado ninguna prueba (tests=0)."
    fallos=1
  fi
  if [ "$((10#$casos))" -ne "$((10#$tests))" ]; then
    echo "::error::$clase: el informe declara tests=$tests pero contiene $casos <testcase>."
    fallos=1
  fi
  if [ "$((10#$failures))" -ne 0 ] || [ "$((10#$errors))" -ne 0 ]; then
    echo "::error::$clase tiene pruebas fallidas (failures=$failures, errors=$errors)."
    fallos=1
  fi
  if [ "$((10#$skipped))" -ne 0 ]; then
    echo "::error::$clase tiene pruebas omitidas (skipped=$skipped): un @Ignore deja sin probar una frontera de seguridad."
    fallos=1
  fi
done

if [ "$fallos" -ne 0 ]; then
  echo "::error::Las pruebas de seguridad del núcleo no se han ejecutado y pasado al completo."
  exit 1
fi
echo "Pruebas de seguridad del núcleo ejecutadas y correctas."
