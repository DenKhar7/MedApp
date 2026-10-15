package medicapp.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import kotlin.time.Duration.Companion.seconds
import medicapp.server.config.dbViewQuery
import medicapp.server.interfaces.API_RATE_LIMIT
import org.jetbrains.exposed.sql.transactions.TransactionManager
import io.ktor.server.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import medicapp.server.config.configureDatabases
import medicapp.server.config.loadApiConfig
import medicapp.server.config.loadStorageConfig
import medicapp.server.domain.repository.MedicamentRepository
import medicapp.server.domain.service.MedicamentService
import medicapp.server.infrastructure.db.requests.repository.MedicamentRepositoryMariaDb
import medicapp.server.infrastructure.jobs.launcher.taskScheduler
import medicapp.server.infrastructure.jobs.referentiel.ReferentielUpdateOrchestrator
import medicapp.server.interfaces.configureRouting
import org.slf4j.event.Level
import medicapp.server.infrastructure.jobs.referentiel.ReferentielJobs
import io.ktor.server.config.*
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

suspend fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "import-referentiels" -> {
            println("[CLI] Import initial des référentiels")

            // Ktor charge automatiquement application.yaml
            val config = ApplicationConfig("application.yaml")

            // Configurer le storage
            val storageConfig = loadStorageConfig(config)

            // Configurer les bases de données
            configureDatabases(config)

            // Lancer l'import
            val orchestrator = ReferentielUpdateOrchestrator(storageConfig)
            orchestrator.init()

            println("[CLI] Import terminé")
            return
        }
    }
    startServer()
}

fun startServer() {
    // Charger la configuration depuis application.yaml
    val config = ApplicationConfig("application.yaml")

    // Récupérer les paramètres de déploiement
    val deploymentConfig = config.config("ktor.deployment")
    val serverPort = deploymentConfig.property("port").getString().toInt()
    val serverHost = deploymentConfig.property("host").getString()

    // Récupérer les paramètres Netty (avec valeurs par défaut)
    val serverRunningLimit = deploymentConfig.propertyOrNull("runningLimit")?.getString()?.toInt() ?: 10
    val serverResponseWriteTimeout = deploymentConfig.propertyOrNull("responseWriteTimeoutSeconds")?.getString()?.toInt() ?: 60
    val serverRequestReadTimeout = deploymentConfig.propertyOrNull("requestReadTimeoutSeconds")?.getString()?.toInt() ?: 60
    val serverConnectionGroupSize = deploymentConfig.propertyOrNull("connectionGroupSize")?.getString()?.toInt() ?: 2
    val serverWorkerGroupSize = deploymentConfig.propertyOrNull("workerGroupSize")?.getString()?.toInt() ?: 5
    val serverCallGroupSize = deploymentConfig.propertyOrNull("callGroupSize")?.getString()?.toInt() ?: 10

    // Configuration du serveur
    embeddedServer(Netty, configure = {
        connectors.add(EngineConnectorBuilder().apply {
            host = serverHost
            port = serverPort
        })

        connectionGroupSize = serverConnectionGroupSize
        workerGroupSize = serverWorkerGroupSize
        callGroupSize = serverCallGroupSize
        runningLimit = serverRunningLimit
        responseWriteTimeoutSeconds = serverResponseWriteTimeout
        requestReadTimeoutSeconds = serverRequestReadTimeout

    }) {
        module(config)
    }.start(wait = true)  // Démarre et attend (bloque le thread)
}


const val DEFAULT_RATE_LIMIT_PER_MINUTE = 120

/** Vérifie que la base lue par l'API répond (utilisé par /health pour que le healthcheck soit réel). */
private suspend fun isDatabaseReachable(): Boolean =
    runCatching {
        dbViewQuery { TransactionManager.current().exec("SELECT 1") }
        true
    }.getOrDefault(false)

fun Application.module(
    config: ApplicationConfig = environment.config,  // Par défaut, utilise la config de l'environment
    repository: MedicamentRepository? = null,
    enableDatabase: Boolean = true,
    enableJobs: Boolean = true,
    /** Surcharge du test de santé de /health (par défaut : vérifie la base si elle est activée). */
    healthCheck: (suspend () -> Boolean)? = null,
    ) {

    // API publique en lecture seule, sans cookie ni authentification : pas de credentials en CORS.
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        maxAgeInSeconds = 3600
    }

    // Le serveur n'est joignable que via Caddy (port lié à 127.0.0.1) : X-Forwarded-For est donc fiable.
    install(XForwardedHeaders)

    // Limitation de débit par adresse IP cliente. Réglable via api.rate-limit.per-minute.
    val rateLimitPerMinute = config.propertyOrNull("api.rate-limit.per-minute")
        ?.getString()?.toIntOrNull()
        ?: DEFAULT_RATE_LIMIT_PER_MINUTE
    install(RateLimit) {
        register(API_RATE_LIMIT) {
            rateLimiter(limit = rateLimitPerMinute, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }


    configureSerialization()

    if (enableDatabase) {
        // Option: passe createSchema=true uniquement en dev
        configureDatabases(config)
    }

    // Si on injecte un repo (tests), on l'utilise.
    // Sinon, on crée le repo MariaDB (prod).
    val repo = repository ?: MedicamentRepositoryMariaDb()
    val service = MedicamentService(repo)

    // Configuration de l'API
    val apiConfig = loadApiConfig(config)

    // Configuration des routes
    configureRouting(
        service,
        apiConfig,
        healthCheck = healthCheck ?: if (enableDatabase) ::isDatabaseReachable else ({ true })
    )


    if (enableJobs){
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val storageConfig = loadStorageConfig(config)

        val orchestrator = ReferentielUpdateOrchestrator(storageConfig)

        val jobs = ReferentielJobs(orchestrator)

        // Premier remplissage de la base sans attendre 0h/8h : RUN_JOBS_ON_START=true, puis le remettre à false.
        if (System.getenv("RUN_JOBS_ON_START") == "true") {
            monitor.subscribe(ApplicationStarted) {
                jobScope.launch {
                    println("[Scheduler] RUN_JOBS_ON_START : exécution immédiate des imports")
                    jobs.updateSmsJob()
                    jobs.updateRuimJob()
                }
            }
        }

        monitor.subscribe(ApplicationStarted) {

            println("[Scheduler] Démarrage du serveur,  planification du job à 0h")

            taskScheduler(
                hour = 0,
                minute = 0,
                second = 0,
                scope = jobScope
            ) {
                println("[Scheduler] Exécution du updateSmsJob (00h00)")
                jobs.updateSmsJob()
            }
        }


        monitor.subscribe(ApplicationStarted) {

            println("[Scheduler] Démarrage du serveur, planification du job à 8h")

            taskScheduler(
                hour = 8,
                minute = 0,
                second = 0,
                scope = jobScope
            ) {
                println("[Scheduler] Exécution du updateRuimJob (08h00)")
                jobs.updateRuimJob()
            }
        }
    }
}
