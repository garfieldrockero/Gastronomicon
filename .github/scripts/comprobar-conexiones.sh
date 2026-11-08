#!/usr/bin/env bash
# Solo UrlConnectionTransport puede abrir conexiones de red. La frontera del HTTP local
# (LocalNetwork, ServerUrl y el transporte) solo sirve si todo el tráfico pasa por ella;
# cualquier otra conexión se la saltaría. Se revisa TODO el código fuente de app/src y
# core/src (.kt y .java), salvo las pruebas (*/test/* y */androidTest/*), sin compilar.
#
# Además de las APIs, se comprueban dos barreras:
#  - Ningún *.gradle.kts de app/ o core/ puede usar sourceSets, srcDir ni productFlavors:
#    si moviera las fuentes a otro directorio, este barrido dejaría de verlas sin avisar.
#  - Fuera del transporte no se admite «import java.net.*» (salvo java.net.URI y
#    java.net.URISyntaxException, que solo analizan texto), ni «import javax.net.*» ni
#    «import java.nio.channels.*». Así se cubren los alias («import X as Y») sin tener
#    que perseguir cada nombre.
#
# Antes de buscar se quitan los comentarios (// , /* */ anidados y KDoc), respetando las
# cadenas ("…", """…""" y literales de carácter), así que un comentario que nombre
# HttpURLConnection no da falso positivo. Las cadenas SÍ se revisan, a propósito: un
# Class.forName("java.net.HttpURLConnection") también debe fallar.
#
# Patrones prohibidos (el carácter previo no puede ser letra, dígito o «_», para no
# confundir «URL(» con «URI(» ni con un identificador más largo):
#   openConnection(            cualquier llamada a openConnection
#   .toURL(                    URI.toURL(), paso previo a abrir la conexión
#   URL(                       constructor de java.net.URL (no coincide con URI()
#   HttpURLConnection, HttpsURLConnection, URLConnection
#   Socket(  SSLSocket(  ServerSocket(  DatagramSocket(
#   SocketChannel, ServerSocketChannel, AsynchronousSocketChannel, DatagramChannel
#   SSLContext  SSLSocketFactory  SocketFactory  createSocket(  HttpClient
#   java.net.http              cliente HTTP del JDK 11+
#   okhttp3                    OkHttp (no es dependencia aprobada)
# Es una red contra despistes, no una frontera: la reflexión, JNI o un nombre montado
# en tiempo de ejecución se escapan de una lista negra textual. Las importaciones
# cierran el hueco de los alias, que es el más fácil de usar sin querer.
# Uso: comprobar-conexiones.sh [raíz-del-repositorio]
set -euo pipefail

# shellcheck source=herramientas-ci.sh
. "$(dirname "${BASH_SOURCE[0]}")/herramientas-ci.sh"

raiz="${1:-.}"
cd "$raiz"
permitido=core/src/main/kotlin/io/github/garfieldrockero/gastronomicon/core/network/UrlConnectionTransport.kt

# Las fuentes solo se buscan en los directorios por defecto: si un build.gradle.kts
# añadiera sourceSets, srcDir o productFlavors, habría código fuera del barrido.
for gradle in app/build.gradle.kts core/build.gradle.kts; do
  if [ ! -f "$gradle" ]; then
    echo "::error::No existe $gradle: no se puede comprobar que no mueva los directorios de fuentes."
    exit 1
  fi
  # Los comentarios de línea no cuentan: así se puede explicar la regla en el propio
  # fichero sin que el barrido se dispare solo. Solo se corta un «//» al inicio o tras
  # un espacio, para no trocear cadenas como «https://…». Sin distinguir mayúsculas,
  # porque «java.setSrcDirs(…)» también mueve las fuentes.
  if sed -E 's,(^|[[:space:]])//.*$,,' "$gradle" | grep -niE 'sourceSets|srcDir|productFlavors' >/dev/null; then
    echo "::error::«$gradle» usa sourceSets, srcDir o productFlavors. Este barrido solo lee los directorios por defecto y dejaría código sin revisar; amplía antes el script."
    exit 1
  fi
done
for directorio in app/src core/src; do
  if [ ! -d "$directorio" ]; then
    echo "::error::No existe $directorio: no se puede dar por válida la comprobación."
    exit 1
  fi
done

programa=$(cat <<'AWK'
# Devuelve la línea sin comentarios. «bloque» (profundidad de /* */) y «crudo» (dentro de
# """) se arrastran de una línea a la siguiente.
function limpiar(s,   i, n, c, d, t, out, ap) {
  ap = sprintf("%c", 39)
  out = ""; n = length(s); i = 1
  while (i <= n) {
    c = substr(s, i, 1); d = substr(s, i, 2); t = substr(s, i, 3)
    if (bloque > 0) {
      if (d == "*/") { bloque--; i += 2; out = out "  " }
      else if (d == "/*") { bloque++; i += 2; out = out "  " }
      else { i++; out = out " " }
      continue
    }
    if (crudo) {
      if (t == "\"\"\"") { crudo = 0; out = out t; i += 3 } else { out = out c; i++ }
      continue
    }
    if (d == "//") break
    if (d == "/*") { bloque = 1; i += 2; out = out "  "; continue }
    if (t == "\"\"\"") { crudo = 1; out = out t; i += 3; continue }
    if (c == "\"") {
      out = out c; i++
      while (i <= n) {
        c = substr(s, i, 1); out = out c; i++
        if (c == "\\") { out = out substr(s, i, 1); i++ }
        else if (c == "\"") break
      }
      continue
    }
    if (c == ap) {
      # Literal de carácter: '"', '\'' , '\n', 'a'. Se copia entero para que '"' no abra una cadena.
      if (substr(s, i + 1, 1) == "\\") {
        j = index(substr(s, i + 3), ap)
        if (j > 0) { out = out substr(s, i, j + 3); i += j + 3; continue }
      } else if (substr(s, i + 2, 1) == ap) {
        out = out substr(s, i, 3); i += 3; continue
      }
    }
    out = out c; i++
  }
  return out
}
FNR == 1 { bloque = 0; crudo = 0 }
{
  l = limpiar($0)
  sospechosa = 0
  if (l ~ /(^|[^A-Za-z0-9_])openConnection[ \t]*\(/ ||
      l ~ /\.toURL[ \t]*\(/ ||
      l ~ /(^|[^A-Za-z0-9_])URL[ \t]*\(/ ||
      l ~ /(^|[^A-Za-z0-9_])(Http|Https)?URLConnection([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])(SSL|Server|Datagram)?Socket[ \t]*\(/ ||
      l ~ /(^|[^A-Za-z0-9_])(Asynchronous|Server)?SocketChannel([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])DatagramChannel([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])(SSLContext|SSLSocketFactory|SocketFactory)([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])createSocket[ \t]*\(/ ||
      l ~ /(^|[^A-Za-z0-9_])HttpClient([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])(AndroidHttpClient|DefaultHttpClient)([^A-Za-z0-9_]|$)/ ||
      l ~ /android\.net\.http\./ ||
      l ~ /(^|[^A-Za-z0-9_])DownloadManager([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])WebView([^A-Za-z0-9_]|$)/ ||
      l ~ /(^|[^A-Za-z0-9_])loadUrl[ \t]*\(/ ||
      l ~ /java\.net\.http/ ||
      l ~ /okhttp3/)
    sospechosa = 1
  # Nombres de paquete de red, con import o completamente cualificados: fuera del
  # transporte solo se admiten java.net.URI y java.net.URISyntaxException, que
  # analizan texto y no abren conexiones. Así también caen «import java.net.URL as U»,
  # java.net.ResponseCache, Authenticator, CookieHandler y ProxySelector, que cambian
  # el comportamiento global de HttpURLConnection sin crear sockets.
  descartable = l
  gsub(/java\.net\.(URI|URISyntaxException)([^A-Za-z0-9_]|$)/, " ", descartable)
  if (descartable ~ /java\.net\.|javax\.net\.|java\.nio\.channels\./)
    sospechosa = 1
  if (sospechosa)
    print FILENAME ":" FNR ": " $0
}
AWK
)

hallazgos=""
revisados=0
while IFS= read -r -d '' archivo; do
  archivo=${archivo#./}
  [ "$archivo" = "$permitido" ] && continue
  revisados=$((revisados + 1))
  salida=$(awk "$programa" "$archivo")
  [ -n "$salida" ] && hallazgos+="$salida"$'\n'
done < <(find app/src core/src -type f \( -name '*.kt' -o -name '*.java' \) \
  ! -path 'app/src/test/*' ! -path 'core/src/test/*' \
  ! -path 'app/src/androidTest/*' ! -path 'core/src/androidTest/*' \
  -print0 2>/dev/null | sort -z)

if [ -n "$hallazgos" ]; then
  echo "::error::solo UrlConnectionTransport puede abrir conexiones; cualquier otra conexión se saltaría la frontera del HTTP local. Líneas afectadas:"
  printf '%s' "$hallazgos" | volcar_seguro
  exit 1
fi
if [ ! -f "$permitido" ]; then
  echo "::error::No existe $permitido: no se puede dar por válida la comprobación."
  exit 1
fi
if [ "$revisados" -eq 0 ]; then
  echo "::error::No se ha revisado ningún fichero de app/src ni core/src; el barrido no demuestra nada."
  exit 1
fi
echo "Ninguna conexión de red fuera de UrlConnectionTransport en todo app/src y core/src (excluidas solo las raíces test y androidTest), sin sourceSets, srcDir ni productFlavors en app/ y core/, y sin java.net (salvo URI y URISyntaxException), javax.net ni java.nio.channels fuera del transporte, ni con import ni con nombres cualificados."
