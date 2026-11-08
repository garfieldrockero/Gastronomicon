package io.github.garfieldrockero.gastronomicon.core.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.garfieldrockero.gastronomicon.core.config.ApiToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pruebas del transporte sobre el HttpServer que trae el JDK: cero
 * dependencias nuevas y control total de cabeceras, códigos y redirecciones.
 *
 * Todo va por HTTP plano en 127.0.0.1, que es justo el hueco que deja abierto
 * el constructor «internal» del transporte; el camino público (create) pasa
 * por ServerUrl y solo acepta HTTPS, o HTTP hacia una IP privada si se pide
 * y con un proveedor de red que diga que el móvil está en casa.
 */
class UrlConnectionTransportTest {

    private val testToken = ApiToken("token-de-prueba-1234")

    private lateinit var server: HttpServer

    /** Segundo servidor al que la app nunca debería pedir nada. */
    private lateinit var otherServer: HttpServer

    /**
     * Rutas que monta cada prueba sobre el servidor principal. Se escriben en
     * el hilo de la prueba y se leen en el del HttpServer.
     */
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()

    // Los manejadores corren en el hilo del HttpServer y las aserciones en el
    // de la prueba: sin tipos seguros entre hilos, la prueba podría leer un
    // valor viejo y fallar (o pasar) por casualidad.
    private val authorizationsReceived: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val otherAuthorizationsReceived: MutableList<String?> =
        Collections.synchronizedList(mutableListOf())
    private val requestsToMain = AtomicInteger()
    private val requestsToOther = AtomicInteger()

    @Before
    fun startServers() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requestsToMain.incrementAndGet()
            val handler: (HttpExchange) -> Unit = routes[exchange.requestURI.path]
                ?: { e -> respond(e, 404) }
            handler(exchange)
        }
        server.start()

        otherServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        otherServer.createContext("/") { exchange ->
            requestsToOther.incrementAndGet()
            otherAuthorizationsReceived.add(exchange.requestHeaders.getFirst("Authorization"))
            respond(exchange, 200, "{}".toByteArray())
        }
        otherServer.start()
    }

    @After
    fun stopServers() {
        // runCatching porque una prueba ya puede haber parado su servidor.
        runCatching { server.stop(0) }
        runCatching { otherServer.stop(0) }
    }

    // ---------------------------------------------------------------- básicos

    @Test
    fun `la peticion lleva el token en Authorization y pide json`() {
        var authorization: String? = null
        var accept: String? = null
        routes["/saluda"] = { e ->
            authorization = e.requestHeaders.getFirst("Authorization")
            accept = e.requestHeaders.getFirst("Accept")
            respond(e, 200, "{\"hola\":true}".toByteArray())
        }
        val response = newTransport().execute(HttpRequest(HttpMethod.GET, "/saluda"))
        assertEquals(200, response.statusCode)
        assertEquals("Bearer token-de-prueba-1234", authorization)
        assertEquals("application/json", accept)
        assertEquals("{\"hola\":true}", response.bodyAsText())
        assertEquals("application/json", response.header("Content-Type"))
        assertTrue(response.isSuccess)
    }

    @Test
    fun `el metodo, las cabeceras propias y el cuerpo llegan al servidor`() {
        val received = mutableListOf<Triple<String, String, String>>()
        routes["/eco"] = { e ->
            received.add(
                Triple(
                    e.requestMethod,
                    e.requestHeaders.getFirst("Content-Type") ?: "",
                    String(e.requestBody.readBytes()),
                ),
            )
            respond(e, 201, "eco".toByteArray())
        }
        val response = newTransport().execute(
            HttpRequest(
                HttpMethod.POST,
                "/eco",
                mapOf("Content-Type" to "application/json"),
                "{\"eco\":true}".toByteArray(),
            ),
        )
        assertEquals(201, response.statusCode)
        assertEquals(listOf(Triple("POST", "application/json", "{\"eco\":true}")), received)
        assertEquals("eco", response.bodyAsText())
    }

    // ---------------------------------------------------------------- conexiones

    @Test
    fun `dos peticiones seguidas con el mismo transporte responden bien y llevan el token`() {
        routes["/primera"] = { e ->
            authorizationsReceived.add(e.requestHeaders.getFirst("Authorization"))
            respond(e, 200, "{\"orden\":1}".toByteArray())
        }
        routes["/segunda"] = { e ->
            authorizationsReceived.add(e.requestHeaders.getFirst("Authorization"))
            respond(e, 200, "{\"orden\":2}".toByteArray())
        }
        val client = newTransport()
        val first = client.execute(HttpRequest(HttpMethod.GET, "/primera"))
        val second = client.execute(HttpRequest(HttpMethod.GET, "/segunda"))
        assertEquals("{\"orden\":1}", first.bodyAsText())
        assertEquals("{\"orden\":2}", second.bodyAsText())
        // Aunque la conexión se reutilice, la cabecera se pone en cada petición.
        assertEquals(
            listOf("Bearer token-de-prueba-1234", "Bearer token-de-prueba-1234"),
            authorizationsReceived,
        )
        assertEquals(2, requestsToMain.get())
    }

    @Test
    fun `una respuesta leida entera deja la conexion lista para la siguiente`() {
        // El puerto de origen del cliente identifica la conexión TCP: si es el
        // mismo en las dos peticiones, la segunda no ha abierto otra.
        val clientPorts: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        routes["/reutiliza"] = { e ->
            clientPorts.add(e.remoteAddress.port)
            respond(e, 200, "{\"ok\":true}".toByteArray())
        }
        val client = newTransport()
        repeat(2) {
            assertEquals(200, client.execute(HttpRequest(HttpMethod.GET, "/reutiliza")).statusCode)
        }
        assertEquals(2, clientPorts.size)
        assertEquals("La segunda petición ha abierto otra conexión", clientPorts[0], clientPorts[1])
    }

    @Test
    fun `tras un error http la siguiente peticion funciona`() {
        // El error cierra su conexión: la siguiente no puede heredar un
        // cuerpo de error sin leer.
        routes["/falla"] = { e -> respond(e, 500, "{\"detail\":\"roto\"}".toByteArray()) }
        routes["/bien"] = { e -> respond(e, 200, "{\"ok\":true}".toByteArray()) }
        val client = newTransport()
        assertEquals(200, client.execute(HttpRequest(HttpMethod.GET, "/bien")).statusCode)
        try {
            client.execute(HttpRequest(HttpMethod.GET, "/falla"))
            throw AssertionError("Se esperaba NetworkError.HttpError")
        } catch (expected: NetworkError.HttpError) {
            assertEquals(500, expected.statusCode)
        }
        val after = client.execute(HttpRequest(HttpMethod.GET, "/bien"))
        assertEquals(200, after.statusCode)
        assertEquals("{\"ok\":true}", after.bodyAsText())
    }

    @Test
    fun `tras cortar una respuesta demasiado grande la siguiente peticion funciona`() {
        // Cortar a mitad de cuerpo deja bytes sin leer en la conexión: si
        // volviera al pool, la siguiente respuesta empezaría con basura.
        routes["/grande-y-luego"] = { e -> respond(e, 200, ByteArray(20_000)) }
        routes["/pequena"] = { e -> respond(e, 200, "{\"ok\":true}".toByteArray()) }
        val client = newTransport(maxResponseBytes = 512)
        assertEquals(200, client.execute(HttpRequest(HttpMethod.GET, "/pequena")).statusCode)
        try {
            client.execute(HttpRequest(HttpMethod.GET, "/grande-y-luego"))
            throw AssertionError("Se esperaba NetworkError.ResponseTooLarge")
        } catch (expected: NetworkError.ResponseTooLarge) {
        }
        val after = client.execute(HttpRequest(HttpMethod.GET, "/pequena"))
        assertEquals(200, after.statusCode)
        assertEquals("{\"ok\":true}", after.bodyAsText())
    }

    @Test
    fun `tras cortar a mitad una respuesta troceada la siguiente peticion no hereda sus bytes`() {
        // Sin Content-Length el límite salta a mitad de lectura, con bytes del
        // cuerpo aún en la conexión: si volviera al pool, la siguiente
        // respuesta se leería mezclada con ellos.
        routes["/troceada-grande"] = { e ->
            try {
                e.sendResponseHeaders(200, 0)
                e.responseBody.use { output -> output.write(ByteArray(20_000) { 'x'.code.toByte() }) }
            } catch (ignored: IOException) {
                // El cliente corta al llegar a su límite de bytes: normal.
            } finally {
                runCatching { e.close() }
            }
        }
        routes["/despues"] = { e -> respond(e, 200, "{\"limpia\":true}".toByteArray()) }
        val client = newTransport(maxResponseBytes = 512)
        try {
            client.execute(HttpRequest(HttpMethod.GET, "/troceada-grande"))
            throw AssertionError("Se esperaba NetworkError.ResponseTooLarge")
        } catch (expected: NetworkError.ResponseTooLarge) {
        }
        val after = client.execute(HttpRequest(HttpMethod.GET, "/despues"))
        assertEquals(200, after.statusCode)
        assertEquals("{\"limpia\":true}", after.bodyAsText())
    }

    // ---------------------------------------------------------------- errores HTTP

    @Test
    fun `el 401 se mapea a un error de token`() {
        routes["/privado"] = { e -> respond(e, 401) }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/privado"))
            throw AssertionError("Se esperaba NetworkError.HttpError")
        } catch (expected: NetworkError.HttpError) {
            assertEquals(401, expected.statusCode)
            assertTrue(expected.message.orEmpty().contains("token"))
        }
    }

    @Test
    fun `los codigos de error http se mapean con su numero`() {
        routes["/prohibido"] = { e -> respond(e, 403) }
        routes["/no-hay"] = { e -> respond(e, 404) }
        routes["/incompleto"] = { e -> respond(e, 422) }
        routes["/explota"] = { e -> respond(e, 500) }
        val client = newTransport()
        for ((path, code) in listOf(
            "/prohibido" to 403,
            "/no-hay" to 404,
            "/incompleto" to 422,
            "/explota" to 500,
        )) {
            try {
                client.execute(HttpRequest(HttpMethod.GET, path))
                throw AssertionError("Se esperaba error $code en $path")
            } catch (expected: NetworkError.HttpError) {
                assertEquals(code, expected.statusCode)
                // El número ayuda a diagnosticar y no es ningún secreto.
                assertTrue(expected.message.orEmpty().contains(code.toString()))
            }
        }
    }

    // ---------------------------------------------------------------- redirecciones

    @Test
    fun `la redireccion al mismo origen se sigue y vuelve a llevar el token`() {
        routes["/salta"] = { e ->
            authorizationsReceived.add(e.requestHeaders.getFirst("Authorization"))
            respond(e, 307, headers = mapOf("Location" to "/final"))
        }
        routes["/final"] = { e ->
            authorizationsReceived.add(e.requestHeaders.getFirst("Authorization"))
            respond(e, 200, "{\"donde\":\"final\"}".toByteArray())
        }
        val response = newTransport().execute(HttpRequest(HttpMethod.GET, "/salta"))
        assertEquals(200, response.statusCode)
        assertEquals("{\"donde\":\"final\"}", response.bodyAsText())
        assertEquals(
            listOf("Bearer token-de-prueba-1234", "Bearer token-de-prueba-1234"),
            authorizationsReceived,
        )
    }

    @Test
    fun `la redireccion absoluta con puerto explicito al mismo origen se sigue`() {
        routes["/salta-absoluto"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "http://127.0.0.1:${server.address.port}/final"))
        }
        routes["/final"] = { e -> respond(e, 200, "{\"donde\":\"absoluta\"}".toByteArray()) }
        val response = newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-absoluto"))
        assertEquals(200, response.statusCode)
        assertEquals("{\"donde\":\"absoluta\"}", response.bodyAsText())
    }

    @Test
    fun `la redireccion 303 cambia la peticion a GET`() {
        val seen = mutableListOf<Pair<String, String>>()
        routes["/salta-303"] = { e ->
            e.requestBody.readBytes() // se drena antes de responder
            respond(e, 303, headers = mapOf("Location" to "/recibe"))
        }
        routes["/recibe"] = { e ->
            seen.add(e.requestMethod to String(e.requestBody.readBytes()))
            respond(e, 200, "{}".toByteArray())
        }
        val response = newTransport().execute(
            HttpRequest(
                HttpMethod.POST,
                "/salta-303",
                mapOf("Content-Type" to "application/json"),
                "{\"a\":1}".toByteArray(),
            ),
        )
        assertEquals(200, response.statusCode)
        // 303 obliga a GET sin cuerpo, aunque la original fuera un POST.
        assertEquals(listOf("GET" to ""), seen)
    }

    @Test
    fun `la redireccion 307 conserva el metodo, el cuerpo y el Content-Type`() {
        val seen = mutableListOf<Triple<String, String, String>>()
        routes["/salta-307"] = { e ->
            e.requestBody.readBytes()
            respond(e, 307, headers = mapOf("Location" to "/recibe"))
        }
        routes["/recibe"] = { e ->
            seen.add(
                Triple(
                    e.requestMethod,
                    e.requestHeaders.getFirst("Content-Type") ?: "",
                    String(e.requestBody.readBytes()),
                ),
            )
            respond(e, 200, "{}".toByteArray())
        }
        val response = newTransport().execute(
            HttpRequest(
                HttpMethod.POST,
                "/salta-307",
                mapOf("Content-Type" to "application/json"),
                "{\"a\":1}".toByteArray(),
            ),
        )
        assertEquals(200, response.statusCode)
        assertEquals(listOf(Triple("POST", "application/json", "{\"a\":1}")), seen)
    }

    @Test
    fun `un 301 o un 302 sobre una escritura se rechaza sin repetirla`() {
        // Casi todos los clientes convierten la escritura en un GET sin cuerpo
        // al seguir un 301 o un 302: aquí se corta con un error visible y el
        // destino no recibe nada.
        val landed = AtomicInteger()
        routes["/destino-escritura"] = { e ->
            landed.incrementAndGet()
            respond(e, 200, "{}".toByteArray())
        }
        val cases = listOf(
            HttpMethod.POST to 301,
            HttpMethod.POST to 302,
            HttpMethod.PUT to 301,
            HttpMethod.DELETE to 302,
        )
        for ((method, status) in cases) {
            routes["/escritura-$status"] = { e ->
                e.requestBody.readBytes()
                respond(e, status, headers = mapOf("Location" to "/destino-escritura"))
            }
            try {
                newTransport().execute(
                    HttpRequest(
                        method,
                        "/escritura-$status",
                        mapOf("Content-Type" to "application/json"),
                        "{\"a\":1}".toByteArray(),
                    ),
                )
                throw AssertionError("Se esperaba NetworkError.RedirectRejected con $method y $status")
            } catch (expected: NetworkError.RedirectRejected) {
            }
        }
        // Una sola petición por caso: la escritura original y nada más.
        assertEquals(cases.size, requestsToMain.get())
        assertEquals(0, landed.get())
    }

    @Test
    fun `con la base en una subruta se sigue la redireccion que queda dentro`() {
        routes["/mealie/api/salta"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "/mealie/api/final"))
        }
        routes["/mealie/api/salta-relativa"] = { e ->
            // Relativa al documento: se resuelve a /mealie/api/final.
            respond(e, 302, headers = mapOf("Location" to "final"))
        }
        routes["/mealie/api/final"] = { e -> respond(e, 200, "{\"donde\":\"dentro\"}".toByteArray()) }
        val client = newTransport(base = URI("http://127.0.0.1:${server.address.port}/mealie"))
        for (path in listOf("/api/salta", "/api/salta-relativa")) {
            val response = client.execute(HttpRequest(HttpMethod.GET, path))
            assertEquals(200, response.statusCode)
            assertEquals("{\"donde\":\"dentro\"}", response.bodyAsText())
        }
    }

    @Test
    fun `con la base en una subruta se rechaza la redireccion que sale de ella`() {
        // Ninguno de estos destinos puede recibir la petición: otra ruta, la
        // raíz, un prefijo de texto que no es el mismo segmento y dos intentos
        // de escapar con puntos.
        val outside = listOf(
            "/otra/final",
            "/",
            "/mealiex/api/final",
            "/mealie/../otra/final",
            "/mealie/%2e%2e/otra/final",
            "../../otra/final",
            // «..;» lo leen algunos servidores como «..»; %25 doble-codifica.
            "/mealie/..;/otra/final",
            "/mealie/%252e%252e/otra/final",
        )
        val client = newTransport(base = URI("http://127.0.0.1:${server.address.port}/mealie"))
        for ((index, location) in outside.withIndex()) {
            routes["/mealie/api/fuera-$index"] = { e ->
                respond(e, 302, headers = mapOf("Location" to location))
            }
            try {
                client.execute(HttpRequest(HttpMethod.GET, "/api/fuera-$index"))
                throw AssertionError("Se esperaba NetworkError.RedirectRejected hacia «$location»")
            } catch (expected: NetworkError.RedirectRejected) {
            }
        }
        // Solo llegan las peticiones originales: ningún destino se ha pedido.
        assertEquals(outside.size, requestsToMain.get())
    }

    @Test
    fun `en http en claro nunca se consulta el proxy del sistema`() {
        // Un proxy (manual, PAC o de MDM) recibiría el token sin cifrar y
        // anularía la frontera de las IP privadas.
        val consulted = AtomicInteger()
        val previous = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: URI?): List<java.net.Proxy> {
                consulted.incrementAndGet()
                return listOf(java.net.Proxy.NO_PROXY)
            }

            override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: IOException?) {}
        })
        try {
            routes["/ping"] = { e -> respond(e, 200, "{}".toByteArray()) }
            // Una redirección al mismo origen abre otra conexión: también sin proxy.
            routes["/salta"] = { e -> respond(e, 302, headers = mapOf("Location" to "/ping")) }
            val response = newTransport().execute(HttpRequest(HttpMethod.GET, "/ping"))
            assertEquals(200, response.statusCode)
            val redirected = newTransport().execute(HttpRequest(HttpMethod.GET, "/salta"))
            assertEquals(200, redirected.statusCode)
            assertEquals(0, consulted.get())
        } finally {
            java.net.ProxySelector.setDefault(previous)
        }
    }

    @Test
    fun `con la base en la raiz se sigue la redireccion a cualquier ruta del mismo origen`() {
        routes["/salta-lejos"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "/otra/aplicacion/final"))
        }
        routes["/otra/aplicacion/final"] = { e -> respond(e, 200, "{}".toByteArray()) }
        val response = newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-lejos"))
        assertEquals(200, response.statusCode)
        assertEquals(2, requestsToMain.get())
    }

    @Test
    fun `redirect inside a multi segment base is accepted with or without trailing slash`() {
        val basePaths = listOf("/cocina/postres", "/cocina/postres/")
        for ((index, basePath) in basePaths.withIndex()) {
            routes["/cocina/postres/api/salta-$index"] = { e ->
                authorizationsReceived.add(e.requestHeaders.getFirst("Authorization"))
                respond(
                    e,
                    302,
                    headers = mapOf("Location" to "/cocina/postres/api/final-$index"),
                )
            }
            routes["/cocina/postres/api/final-$index"] = { e ->
                authorizationsReceived.add(e.requestHeaders.getFirst("Authorization"))
                respond(e, 200, "{\"donde\":\"postres\"}".toByteArray())
            }

            val client = newTransport(
                base = URI("http://127.0.0.1:${server.address.port}$basePath"),
            )
            val response = client.execute(HttpRequest(HttpMethod.GET, "/api/salta-$index"))
            assertEquals(200, response.statusCode)
            assertEquals("{\"donde\":\"postres\"}", response.bodyAsText())
        }

        // La barra final de la base no cambia ni el ámbito ni la concatenación.
        assertEquals(4, requestsToMain.get())
        assertEquals(List(4) { "Bearer ${testToken.value}" }, authorizationsReceived)
        assertEquals(0, requestsToOther.get())
    }

    @Test
    fun `redirect outside a multi segment base is rejected without forwarding Authorization`() {
        val basePaths = listOf("/cocina/postres", "/cocina/postres/")
        for ((baseIndex, basePath) in basePaths.withIndex()) {
            val outsideLocations = listOf(
                "/cocina/otra/final",
                "/cocina/postres-fuera/final",
                "http://127.0.0.1:${otherServer.address.port}/robar",
            )
            for ((locationIndex, location) in outsideLocations.withIndex()) {
                val path = "/api/fuera-$baseIndex-$locationIndex"
                routes["/cocina/postres$path"] = { e ->
                    respond(e, 302, headers = mapOf("Location" to location))
                }
                val client = newTransport(
                    base = URI("http://127.0.0.1:${server.address.port}$basePath"),
                )
                try {
                    client.execute(HttpRequest(HttpMethod.GET, path))
                    throw AssertionError("Se esperaba RedirectRejected hacia «$location»")
                } catch (expected: NetworkError.RedirectRejected) {
                }
            }
        }

        // Solo han salido las seis peticiones iniciales. En particular, el
        // segundo servidor no ha visto ninguna petición ni cabecera con token.
        assertEquals(6, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
        assertTrue(otherAuthorizationsReceived.isEmpty())
    }

    @Test
    fun `la redireccion a otro host se rechaza y no se llega a pedir nada mas`() {
        // «localhost» apunta al mismo sitio que 127.0.0.1, pero el texto del
        // host es distinto: si se siguiera, la segunda peticion llegaría al
        // servidor principal y el contador lo delataría.
        routes["/salta-a-otro-host"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "http://localhost:${server.address.port}/final"))
        }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-a-otro-host"))
            throw AssertionError("Se esperaba NetworkError.RedirectRejected")
        } catch (expected: NetworkError.RedirectRejected) {
        }
        assertEquals(1, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
    }

    @Test
    fun `la redireccion a otro puerto se rechaza sin enviar nada al otro servidor`() {
        // La prueba clave: el destino es otro servidor y el token no puede
        // llegar allí ni una vez.
        routes["/salta-a-otro-puerto"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "http://127.0.0.1:${otherServer.address.port}/robar"))
        }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-a-otro-puerto"))
            throw AssertionError("Se esperaba NetworkError.RedirectRejected")
        } catch (expected: NetworkError.RedirectRejected) {
        }
        assertEquals(0, requestsToOther.get())
        assertEquals(1, requestsToMain.get())
    }

    @Test
    fun `la redireccion con cambio de esquema se rechaza`() {
        // Con una base https cubriría la rebaja a http; con una base http de
        // la red local es justo este caso: un Mealie local que redirige a
        // https da RedirectRejected y se configura con «https://».
        routes["/salta-segura"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "https://127.0.0.1:${server.address.port}/final"))
        }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-segura"))
            throw AssertionError("Se esperaba NetworkError.RedirectRejected")
        } catch (expected: NetworkError.RedirectRejected) {
        }
        assertEquals(1, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
    }

    @Test
    fun `el bucle de redirecciones se corta despues de cinco`() {
        routes["/bucle"] = { e -> respond(e, 302, headers = mapOf("Location" to "/bucle")) }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/bucle"))
            throw AssertionError("Se esperaba NetworkError.TooManyRedirects")
        } catch (expected: NetworkError.TooManyRedirects) {
        }
        // La peticion inicial mas cinco redirecciones seguidas: la sexta se
        // corta antes de salir de la app.
        assertEquals(6, requestsToMain.get())
    }

    @Test
    fun `la redireccion sin destino es una respuesta no valida`() {
        routes["/salta-sin-destino"] = { e -> respond(e, 302) }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-sin-destino"))
            throw AssertionError("Se esperaba NetworkError.InvalidResponse")
        } catch (expected: NetworkError.InvalidResponse) {
        }
    }

    @Test
    fun `un 304 no se sigue y se entrega como error http`() {
        routes["/sin-modificar"] = { e -> respond(e, 304) }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/sin-modificar"))
            throw AssertionError("Se esperaba NetworkError.HttpError")
        } catch (expected: NetworkError.HttpError) {
            assertEquals(304, expected.statusCode)
        }
    }

    @Test
    fun `unlisted 300 and 305 responses are HTTP errors and never follow Location`() {
        // El transporte solo sigue 301, 302, 303, 307 y 308. El resto de 3xx
        // conserva su código en HttpError y Location no se resuelve ni se pide.
        for ((path, status) in listOf("/multiples-opciones" to 300, "/usa-proxy" to 305)) {
            routes[path] = { e ->
                respond(
                    e,
                    status,
                    headers = mapOf(
                        "Location" to "http://127.0.0.1:${otherServer.address.port}/no-seguir",
                    ),
                )
            }
            try {
                newTransport().execute(HttpRequest(HttpMethod.GET, path))
                throw AssertionError("Se esperaba NetworkError.HttpError con $status")
            } catch (expected: NetworkError.HttpError) {
                assertEquals(status, expected.statusCode)
            }
        }
        assertEquals(2, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
        assertTrue(otherAuthorizationsReceived.isEmpty())
    }

    // ---------------------------------------------------------------- límites

    @Test
    fun `el cuerpo con Content-Length que supera el limite se rechaza`() {
        routes["/grande"] = { e -> respond(e, 200, ByteArray(20_000)) }
        try {
            newTransport(maxResponseBytes = 512).execute(HttpRequest(HttpMethod.GET, "/grande"))
            throw AssertionError("Se esperaba NetworkError.ResponseTooLarge")
        } catch (expected: NetworkError.ResponseTooLarge) {
        }
    }

    @Test
    fun `el cuerpo troceado que supera el limite se corta al leer`() {
        // Sin Content-Length (respuesta troceada): el limite tiene que cumplirse
        // mientras se lee, no solo mirando la cabecera.
        routes["/grande-por-partes"] = { e ->
            try {
                e.sendResponseHeaders(200, 0)
                e.responseBody.use { output -> output.write(ByteArray(20_000)) }
            } catch (ignored: IOException) {
                // El cliente corta al llegar a su límite de bytes: normal.
            } finally {
                runCatching { e.close() }
            }
        }
        try {
            newTransport(maxResponseBytes = 512).execute(HttpRequest(HttpMethod.GET, "/grande-por-partes"))
            throw AssertionError("Se esperaba NetworkError.ResponseTooLarge")
        } catch (expected: NetworkError.ResponseTooLarge) {
        }
    }

    @Test
    fun `el tiempo de lectura agotado se mapea a tiempo agotado`() {
        routes["/lento"] = { e ->
            try {
                Thread.sleep(1_500)
                respond(e, 200, "{}".toByteArray())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            newTransport(readTimeoutMs = 300).execute(HttpRequest(HttpMethod.GET, "/lento"))
            throw AssertionError("Se esperaba NetworkError.Timeout")
        } catch (expected: NetworkError.Timeout) {
        }
    }

    @Test
    fun `la conexion rechazada se mapea a sin conexion`() {
        // Se para el segundo servidor para tener un puerto cerrado con seguridad.
        val closedPort = otherServer.address.port
        otherServer.stop(0)
        val client = newTransport(base = URI("http://127.0.0.1:$closedPort"))
        try {
            client.execute(HttpRequest(HttpMethod.GET, "/"))
            throw AssertionError("Se esperaba NetworkError.NoConnection")
        } catch (expected: NetworkError.NoConnection) {
        }
    }

    @Test
    fun `la redireccion con usuario embebido se rechaza`() {
        // Una URL con usuario y contraseña en el «Location» es un intento de
        // meter credenciales en la petición: se corta antes de abrir nada.
        routes["/salta-con-usuario"] = { e ->
            respond(
                e,
                302,
                headers = mapOf(
                    "Location" to "http://usuario:clave@127.0.0.1:${server.address.port}/final",
                ),
            )
        }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-con-usuario"))
            throw AssertionError("Se esperaba NetworkError.RedirectRejected")
        } catch (expected: NetworkError.RedirectRejected) {
        }
        assertEquals(1, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
    }

    @Test
    fun `redirect with empty userInfo is rejected`() {
        // java.net.URI distingue «sin userInfo» de «userInfo vacío»: en ambos
        // Location userInfo no es null y la redirección tiene que cortarse.
        val locations = listOf(
            "http://@127.0.0.1:${server.address.port}/vacio",
            "http://:@127.0.0.1:${server.address.port}/vacio-con-dos-puntos",
        )
        for ((index, location) in locations.withIndex()) {
            routes["/salta-userinfo-vacio-$index"] = { e ->
                respond(e, 302, headers = mapOf("Location" to location))
            }
            try {
                newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-userinfo-vacio-$index"))
                throw AssertionError("Se esperaba RedirectRejected con userInfo vacío")
            } catch (expected: NetworkError.RedirectRejected) {
            }
        }

        // No se ha abierto el destino aunque tenga el mismo origen textual.
        assertEquals(2, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
    }

    @Test
    fun `la redireccion relativa al protocolo a otro puerto se rechaza`() {
        // «//host:puerto/x» hereda el esquema de la base, así que pasaría el
        // filtro de esquema: lo que tiene que frenarla es el puerto. La prueba
        // clave es que el segundo servidor no recibe ni una petición.
        routes["/salta-relativa"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "//127.0.0.1:${otherServer.address.port}/robar"))
        }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-relativa"))
            throw AssertionError("Se esperaba NetworkError.RedirectRejected")
        } catch (expected: NetworkError.RedirectRejected) {
        }
        assertEquals(1, requestsToMain.get())
        assertEquals(0, requestsToOther.get())
    }

    @Test
    fun `un Location que no se puede parsear es una respuesta no valida`() {
        routes["/salta-roto"] = { e ->
            respond(e, 302, headers = mapOf("Location" to "http://[::1"))
        }
        try {
            newTransport().execute(HttpRequest(HttpMethod.GET, "/salta-roto"))
            throw AssertionError("Se esperaba NetworkError.InvalidResponse")
        } catch (expected: NetworkError.InvalidResponse) {
        }
    }

    // ---------------------------------------------------------------- plazos

    @Test
    fun `un Content-Length mentiroso mayor que el limite se rechaza sin leer el cuerpo`() {
        // Declara 100 000 bytes y manda 16: se rechaza por la cabecera, antes
        // de descargar nada y sin esperar a que llegue el resto.
        routes["/mentiroso"] = { e ->
            try {
                e.sendResponseHeaders(200, 100_000)
                e.responseBody.use { it.write(ByteArray(16)) }
            } catch (ignored: IOException) {
                // El cliente corta al ver la cabecera: normal.
            } finally {
                runCatching { e.close() }
            }
        }
        try {
            newTransport(maxResponseBytes = 512).execute(HttpRequest(HttpMethod.GET, "/mentiroso"))
            throw AssertionError("Se esperaba NetworkError.ResponseTooLarge")
        } catch (expected: NetworkError.ResponseTooLarge) {
        }
    }

    @Test
    fun `el plazo total de la peticion se agota aunque cada lectura llegue a tiempo`() {
        // El servidor manda un byte cada 150 ms: ninguna lectura agota el
        // tiempo de lectura (10 s), pero la petición entera pasa del plazo.
        routes["/gotea"] = { e ->
            try {
                e.sendResponseHeaders(200, 0)
                e.responseBody.use { out ->
                    repeat(40) {
                        out.write(ByteArray(1) { 'x'.code.toByte() })
                        out.flush()
                        Thread.sleep(150)
                    }
                }
            } catch (ignored: IOException) {
                // El cliente ya se ha ido: la prueba ha hecho su trabajo.
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { e.close() }
            }
        }
        val started = System.currentTimeMillis()
        try {
            newTransport(readTimeoutMs = 10_000, totalTimeoutMs = 500)
                .execute(HttpRequest(HttpMethod.GET, "/gotea"))
            throw AssertionError("Se esperaba NetworkError.Timeout")
        } catch (expected: NetworkError.Timeout) {
        }
        val elapsed = System.currentTimeMillis() - started
        // Si el plazo no se respetara, esto tardaría los 6 s del cuerpo entero.
        assertTrue("El plazo total no se respetó: $elapsed ms", elapsed < 4_000)
    }

    @Test
    fun `total timeout covers a chain of slow redirects`() {
        // Cada salto responde muy por debajo del tiempo de lectura configurado.
        // Si el plazo se reiniciara en cada redirección, los cinco saltos y el
        // destino final terminarían con un 200 en vez de agotar el plazo común.
        val redirectCount = 5
        for (index in 0 until redirectCount) {
            routes["/cadena-lenta-$index"] = { e ->
                try {
                    Thread.sleep(250)
                    val next = if (index + 1 < redirectCount) {
                        "/cadena-lenta-${index + 1}"
                    } else {
                        "/cadena-lenta-final"
                    }
                    respond(e, 302, headers = mapOf("Location" to next))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    runCatching { e.close() }
                }
            }
        }
        val finalRequests = AtomicInteger()
        routes["/cadena-lenta-final"] = { e ->
            finalRequests.incrementAndGet()
            respond(e, 200, "{}".toByteArray())
        }

        try {
            newTransport(readTimeoutMs = 2_000, totalTimeoutMs = 900)
                .execute(HttpRequest(HttpMethod.GET, "/cadena-lenta-0"))
            throw AssertionError("Se esperaba NetworkError.Timeout en la cadena lenta")
        } catch (expected: NetworkError.Timeout) {
        }

        // Exige al menos un salto seguido para que la prueba no pase por un
        // simple tiempo de lectura agotado en la primera respuesta.
        assertTrue(
            "La cadena no llegó a seguir ninguna redirección",
            requestsToMain.get() >= 2,
        )
        assertEquals(0, finalRequests.get())
    }

    @Test
    fun `un POST sin cuerpo llega como POST y sin cuerpo`() {
        val (method, bodySize) = echoWithoutBody(HttpMethod.POST, "/sin-cuerpo")
        assertEquals("POST", method)
        assertEquals(0, bodySize)
    }

    @Test
    fun `un PUT sin cuerpo llega como PUT y sin cuerpo`() {
        // El JDK podría reintentar una escritura por su cuenta si el cuerpo no
        // está en modo «streaming»; con longitud fija cero no hay reintento.
        val (method, bodySize) = echoWithoutBody(HttpMethod.PUT, "/put-sin-cuerpo")
        assertEquals("PUT", method)
        assertEquals(0, bodySize)
    }

    @Test
    fun `un DELETE sin cuerpo llega como DELETE y sin cuerpo`() {
        val (method, bodySize) = echoWithoutBody(HttpMethod.DELETE, "/delete-sin-cuerpo")
        assertEquals("DELETE", method)
        assertEquals(0, bodySize)
    }

    // ---------------------------------------------------------------- red activa

    @Test
    fun `con http y sin red de casa no sale ninguna peticion`() {
        routes["/app/about"] = { e -> respond(e, 200, "{}".toByteArray()) }
        for (kind in listOf(NetworkKind.Cellular, NetworkKind.None, NetworkKind.Other)) {
            try {
                newTransport(activeNetwork = { kind }).execute(HttpRequest(HttpMethod.GET, "/app/about"))
                throw AssertionError("Se esperaba LocalHttpNotAllowedOnThisNetwork con $kind")
            } catch (expected: NetworkError.LocalHttpNotAllowedOnThisNetwork) {
            }
        }
        // Ni una cabecera ha llegado al servidor: el token no ha salido.
        assertEquals(0, requestsToMain.get())
    }

    @Test
    fun `con http una escritura tampoco sale sin red de casa`() {
        routes["/api/recipes"] = { e -> respond(e, 201, "{}".toByteArray()) }
        try {
            newTransport(activeNetwork = { NetworkKind.Cellular }).execute(
                HttpRequest(
                    HttpMethod.POST,
                    "/api/recipes",
                    mapOf("Content-Type" to "application/json"),
                    "{}".toByteArray(),
                ),
            )
            throw AssertionError("Se esperaba LocalHttpNotAllowedOnThisNetwork")
        } catch (expected: NetworkError.LocalHttpNotAllowedOnThisNetwork) {
        }
        assertEquals(0, requestsToMain.get())
    }

    @Test
    fun `con http y wifi, ethernet o VPN la peticion sale`() {
        routes["/app/about"] = { e -> respond(e, 200, "{}".toByteArray()) }
        for (kind in listOf(NetworkKind.Wifi, NetworkKind.Ethernet, NetworkKind.Vpn)) {
            val response = newTransport(activeNetwork = { kind }).execute(HttpRequest(HttpMethod.GET, "/app/about"))
            assertEquals("Con $kind", 200, response.statusCode)
        }
        assertEquals(3, requestsToMain.get())
    }

    @Test
    fun `si el proveedor de red falla no sale nada`() {
        // Ante la duda, el token no viaja en claro.
        routes["/app/about"] = { e -> respond(e, 200, "{}".toByteArray()) }
        try {
            newTransport(activeNetwork = { throw IllegalStateException("sin servicio") })
                .execute(HttpRequest(HttpMethod.GET, "/app/about"))
            throw AssertionError("Se esperaba LocalHttpNotAllowedOnThisNetwork")
        } catch (expected: NetworkError.LocalHttpNotAllowedOnThisNetwork) {
        }
        assertEquals(0, requestsToMain.get())
    }

    @Test
    fun `la red se consulta antes de cada salto y no solo al empezar`() {
        val calls = AtomicInteger()
        routes["/primera"] = { e -> respond(e, 302, headers = mapOf("Location" to "/segunda")) }
        routes["/segunda"] = { e -> respond(e, 200, "{}".toByteArray()) }
        val response = newTransport(activeNetwork = { calls.incrementAndGet(); NetworkKind.Wifi })
            .execute(HttpRequest(HttpMethod.GET, "/primera"))
        assertEquals(200, response.statusCode)
        assertEquals(2, calls.get())
        assertEquals(2, requestsToMain.get())
    }

    @Test
    fun `si la red pasa a datos moviles entre la peticion y la redireccion se corta`() {
        // El manejador corre en el hilo del servidor: el cambio de red tiene
        // que verse desde el de la prueba.
        val current = AtomicReference(NetworkKind.Wifi)
        routes["/primera"] = { e ->
            current.set(NetworkKind.Cellular)
            respond(e, 302, headers = mapOf("Location" to "/segunda"))
        }
        routes["/segunda"] = { e -> respond(e, 200, "{}".toByteArray()) }
        try {
            newTransport(activeNetwork = { current.get() }).execute(HttpRequest(HttpMethod.GET, "/primera"))
            throw AssertionError("Se esperaba LocalHttpNotAllowedOnThisNetwork")
        } catch (expected: NetworkError.LocalHttpNotAllowedOnThisNetwork) {
        }
        // Solo la primera ha llegado: la segunda no se ha abierto.
        assertEquals(1, requestsToMain.get())
    }

    @Test
    fun `con base https no se consulta la red`() {
        // El servidor de pruebas habla http plano: el TLS fallará, pero lo que
        // se comprueba es que antes nadie le ha preguntado al proveedor.
        val calls = AtomicInteger()
        val transport = newTransport(
            base = URI("https://127.0.0.1:${server.address.port}"),
            // Si el servidor se queda esperando el resto del saludo TLS, que no
            // tarde los 5 s de las demás pruebas en dar Timeout.
            readTimeoutMs = 1_000,
            activeNetwork = {
                calls.incrementAndGet()
                throw AssertionError("Con https no se debe consultar la red")
            },
        )
        try {
            transport.execute(HttpRequest(HttpMethod.GET, "/app/about"))
            throw AssertionError("Se esperaba un fallo de TLS contra un servidor http")
        } catch (expected: NetworkError) {
            assertFalse(expected is NetworkError.LocalHttpNotAllowedOnThisNetwork)
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun `el error de red no permitida lleva un mensaje fijo y sin datos`() {
        val message = NetworkError.LocalHttpNotAllowedOnThisNetwork.message.orEmpty()
        assertTrue(message.contains("HTTPS"))
        assertFalse(message.contains("127.0.0.1"))
        assertFalse(message.contains(testToken.value))
    }

    // ---------------------------------------------------------------- camino público

    @Test
    fun `el camino publico create solo acepta https`() {
        assertCreateRejected("http://127.0.0.1:443")
        // Con https construye sin tocar la red.
        UrlConnectionTransport.create("https://mealie.example.com", testToken)
    }

    @Test
    fun `create rechaza http local sin la opcion`() {
        assertCreateRejected("http://192.168.1.50:9000")
        assertCreateRejected("http://mealie.local")
    }

    @Test
    fun `create con la opcion construye http local sin tocar la red`() {
        // Son direcciones a las que nadie escucha en CI: si create abriera
        // conexión, fallaría o tardaría; solo construye. Tampoco pregunta por
        // la red: eso se hace al enviar, no al construir.
        val calls = AtomicInteger()
        val provider = ActiveNetworkProvider { calls.incrementAndGet(); NetworkKind.Wifi }
        UrlConnectionTransport.create("http://192.168.1.50:9000", testToken, allowLocalHttp = true, activeNetwork = provider)
        UrlConnectionTransport.create("http://[fd00::5]:9000", testToken, allowLocalHttp = true, activeNetwork = provider)
        // https sigue valiendo con la opción activada.
        UrlConnectionTransport.create("https://mealie.example.com", testToken, allowLocalHttp = true, activeNetwork = provider)
        assertEquals(0, calls.get())
    }

    @Test
    fun `create con la opcion rechaza los nombres local`() {
        assertCreateRejected("http://mealie.local", allowLocalHttp = true)
        assertCreateRejected("http://mealie.local:9000", allowLocalHttp = true)
    }

    @Test
    fun `create con la opcion y sin proveedor de red lanza`() {
        // Falla cerrado: no puede existir un transporte http que no mire la red.
        for (url in listOf("http://192.168.1.50:9000", "https://mealie.example.com")) {
            try {
                UrlConnectionTransport.create(url, testToken, allowLocalHttp = true, activeNetwork = null)
                throw AssertionError("Se esperaba que create rechazara la falta de proveedor con $url")
            } catch (expected: IllegalArgumentException) {
                val message = expected.message.orEmpty()
                assertTrue(message.isNotBlank())
                assertFalse(message.contains(url))
                assertFalse(message.contains(testToken.value))
            }
        }
    }

    @Test
    fun `create sin la opcion no necesita proveedor`() {
        UrlConnectionTransport.create("https://mealie.example.com", testToken, activeNetwork = null)
    }

    @Test
    fun `create con la opcion rechaza http que no es local`() {
        assertCreateRejected("http://mealie.example.com", allowLocalHttp = true)
        assertCreateRejected("http://8.8.8.8:9000", allowLocalHttp = true)
        // El HttpServer de estas pruebas escucha en 127.0.0.1: el camino
        // público no debe poder llegar a él por HTTP ni con la opción.
        assertCreateRejected("http://127.0.0.1:${server.address.port}", allowLocalHttp = true)
        assertCreateRejected("http://localhost:${server.address.port}", allowLocalHttp = true)
        assertEquals(0, requestsToMain.get())
    }

    // ---------------------------------------------------------------- ayuda

    /** create tiene que rechazar la URL con un mensaje que no repita la entrada. */
    private fun assertCreateRejected(url: String, allowLocalHttp: Boolean = false) {
        try {
            // Con la opción se pasa un proveedor para que el rechazo sea por la
            // URL y no por la falta de proveedor (que tiene su propia prueba).
            UrlConnectionTransport.create(
                url,
                testToken,
                allowLocalHttp = allowLocalHttp,
                activeNetwork = if (allowLocalHttp) ActiveNetworkProvider { NetworkKind.Wifi } else null,
            )
            throw AssertionError("Se esperaba que create rechazara $url")
        } catch (expected: IllegalArgumentException) {
            val message = expected.message.orEmpty()
            assertTrue(message.isNotBlank())
            assertFalse(message.contains(url))
            assertFalse(message.contains(testToken.value))
        }
    }

    /**
     * Envía una petición sin cuerpo y devuelve el método y el tamaño de cuerpo
     * que vio el servidor. Sirve para comprobar que el transporte no cambia el
     * método ni inventa un cuerpo al declarar la salida.
     */
    private fun echoWithoutBody(method: HttpMethod, path: String): Pair<String, Int> {
        var seenMethod = ""
        var seenBodySize = -1
        routes[path] = { e ->
            seenMethod = e.requestMethod
            seenBodySize = e.requestBody.readBytes().size
            respond(e, 200, "{}".toByteArray())
        }
        val response = newTransport().execute(HttpRequest(method, path))
        assertEquals(200, response.statusCode)
        return seenMethod to seenBodySize
    }

    private fun newTransport(
        base: URI = URI("http://127.0.0.1:${server.address.port}"),
        readTimeoutMs: Int = 5_000,
        maxResponseBytes: Long = 1_000_000,
        totalTimeoutMs: Long = 60_000,
        activeNetwork: ActiveNetworkProvider = ActiveNetworkProvider { NetworkKind.Wifi },
    ): UrlConnectionTransport =
        UrlConnectionTransport(
            baseUri = base,
            token = testToken,
            connectTimeoutMs = 5_000,
            readTimeoutMs = readTimeoutMs,
            maxResponseBytes = maxResponseBytes,
            totalTimeoutMs = totalTimeoutMs,
            activeNetwork = activeNetwork,
        )

    /**
     * Responde en el intercambio. Traga las IOException porque el cliente de
     * alguna prueba se desconecta a propósito (tiempo agotado, límite de
     * bytes): eso no debe ensuciar el resultado de la prueba.
     */
    private fun respond(
        exchange: HttpExchange,
        status: Int,
        payload: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ) {
        try {
            val allHeaders =
                if (payload.isNotEmpty() && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    headers + ("Content-Type" to "application/json")
                } else {
                    headers
                }
            for ((name, value) in allHeaders) exchange.responseHeaders.add(name, value)
            if (status == 204 || status == 304 || payload.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
        } catch (ignored: IOException) {
            // El cliente ya se ha ido: no es un fallo de la prueba.
        } finally {
            runCatching { exchange.close() }
        }
    }
}
