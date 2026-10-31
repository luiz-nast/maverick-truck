// O escudo do Maverick Truck. Ele não corta a internet de nada (o dono, 10-31: "esquece o código que bloqueia a
// internet do app, tenta sempre fazer o app ou site cair fora"). Ele faz duas coisas:
//
// 1. Conta o uso. A cada segundo de tela ligada, vê qual app está na frente (Uso) e, se é o Instagram ou o YouTube (o
//    app, ou um navegador no site), soma na rodada do site. Passou de 7 min, o site fica bloqueado 15 min pelo relógio.
// 2. Ejeta. Enquanto um site está bloqueado e o app dele (ou um navegador no site) está na frente, a tela de bloqueio
//    aparece por cima (Cobertura), com um botão que vai pra tela inicial. Só sobre o Instagram e o YouTube: nunca sobre
//    banco, o despertador ou outro app.
//
// Pra saber que um navegador está num site bloqueado, sem serviço de acessibilidade (que o Nubank trava), o escudo é uma
// VPN local que só observa o DNS: todas as perguntas de endereço passam por ele, que anota quais apps perguntaram pelo
// Instagram ou pelo YouTube e manda a resposta de verdade de volta, sem mexer em nada. Nada sai por um servidor de fora.
// Fora do túnel ficam o próprio app, os bancos e o despertador.
package io.github.luiz_nast.maverick.truck

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Tag dos logs do app: `adb logcat -s Maverick`. */
const val TAG = "Maverick"

class Escudo : VpnService() {

    companion object {
        private const val ACAO_LIGAR = "ligar"

        /** O endereço do celular dentro do túnel e o "servidor de DNS" que só existe nele. */
        private const val ENDERECO = "10.215.173.1"
        private const val DNS = "10.215.173.2"

        /** Apps que o escudo nunca toca: os bancos (o Nubank estranha qualquer coisa no meio) e o despertador. */
        private val FORA = listOf(
            "com.nu.production", "com.implacavel.alarme", "br.com.intermedium", "com.itau", "br.com.bb.android",
            "com.bradesco", "com.santander.app", "br.com.gabba.Caixa", "com.mercadopago.wallet", "com.picpay",
        )

        /** DNS de reserva, se a rede não disser o dela. */
        private val RESERVA = listOf("1.1.1.1", "8.8.8.8")

        private const val TIQUE_MS = 1_000L
        private const val GRAVAR_MS = 5_000L
        private const val NOTIFICAR_MS = 5_000L
        private const val ESPERA_DNS_MS = 2_000
        private const val RELER_SERVIDORES_MS = 30_000L

        /** Uma pergunta que esperou mais que isto na fila já foi repetida pelo Android: a velha não vai pro DNS. */
        private const val PERGUNTA_VELHA_MS = 4_000L

        /** Se a leitura do túnel cair, ele é remontado; mais que isto de vezes num minuto, o escudo desiste e avisa. */
        private const val REMONTAGENS_MAX = 5

        /** O escudo está rodando neste processo (o [Estado.ligado] gravado pode dizer "ligado" depois de o app ser morto). */
        @Volatile
        var rodando = false
            private set

        /** Liga o escudo (a permissão da VPN já dada: [VpnService.prepare] devolve null). */
        fun ligar(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, Escudo::class.java).setAction(ACAO_LIGAR))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val consultas = Executors.newFixedThreadPool(6)

    @Volatile
    private var tunel: ParcelFileDescriptor? = null

    /** A saída do túnel de agora: as respostas sempre vão pro túnel atual, mesmo as que chegam depois de uma troca. */
    @Volatile
    private var saida: FileOutputStream? = null
    private val travaDaSaida = Any()
    private val remontagens = ArrayDeque<Long>()

    private var ligado = false
    private var uso: Uso? = null
    private val cobertura by lazy { Cobertura(this) }
    private val medidor by lazy { Medidor(this) }
    private var navegadores = emptySet<String>()
    private var uidsDosApps = emptySet<Int>() // os apps do Instagram e do YouTube: o DNS deles em segundo plano não é uso
    private val ultimaConsulta = ConcurrentHashMap<Site, Consulta>()
    private val uids = HashMap<String, Int?>() // o uid de cada pacote que já esteve na frente
    private var ultimoTique = 0L
    private var gravadoEm = 0L
    private var notificadoEm = 0L
    private var ultimoTexto = ""
    private var avisadoNaFrente: String? = null

    @Volatile
    private var servidores: List<InetAddress> = emptyList()

    @Volatile
    private var servidoresEm = 0L

    private val tique = object : Runnable {
        override fun run() {
            avancar()
            if (ligado) handler.postDelayed(this, TIQUE_MS)
        }
    }

    /** Tela apagada: o uso para de contar (e o bloqueio segue pelo relógio); a tela por cima some. Acesa: volta a contar. */
    private val tela = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.removeCallbacks(tique)
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                avancar()
                cobertura.esconder()
                medidor.esconder()
                ultimoTique = 0L
            } else {
                ultimoTique = 0L
                navegadores = Uso.navegadores(this@Escudo)
                uids.clear()
                handler.post(tique)
            }
        }
    }

    /** A rede mudou (saiu do Wi-Fi pro 4G, por exemplo): os servidores de DNS de verdade são lidos de novo. */
    private val rede = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            servidoresEm = 0L
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            servidoresEm = 0L
        }

        override fun onLost(network: Network) {
            servidoresEm = 0L
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Estado.carregar(this)
        Notificacoes.criarCanais(this)
        // Reiniciado pelo Android (intent nulo) com o escudo desligado pelo dono: nada a fazer
        if (intent == null && !Estado.ligado.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        ligarEscudo()
        return START_STICKY
    }

    private fun ligarEscudo() {
        val tipo = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val emPrimeiroPlano = runCatching {
            ServiceCompat.startForeground(this, Notificacoes.ID_ESTADO, Notificacoes.estado(this, "Starting…"), tipo)
        }.onFailure { Log.w(TAG, "Escudo: o Android não deixou ficar em primeiro plano", it) }.isSuccess
        if (!emPrimeiroPlano) {
            Estado.ligar(false)
            Notificacoes.avisarReligar(this)
            return stopSelf()
        }
        if (prepare(this) != null) {
            Log.w(TAG, "Escudo: sem a permissão da VPN")
            Estado.ligar(false)
            Notificacoes.avisarReligar(this)
            return parar()
        }
        // Ligado de novo com ele já de pé: a notificação "Starting…" de cima vai ser trocada já no próximo tique
        ultimoTexto = ""
        notificadoEm = 0L
        if (!ligado) {
            ligado = true
            rodando = true
            Estado.ligar(true)
            navegadores = Uso.navegadores(this)
            uidsDosApps = Uso.uids(this, Site.entries.flatMap { it.pacotes }.toSet())
            ContextCompat.registerReceiver(
                this, tela,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(rede) }
            ultimoTique = 0L
            montarTunel()
            Log.i(TAG, "Escudo: ligado (${navegadores.size} navegadores)")
        }
        handler.removeCallbacks(tique)
        avancar()
        if (telaLigada()) handler.postDelayed(tique, TIQUE_MS)
    }

    private fun telaLigada() = getSystemService(PowerManager::class.java).isInteractive

    /** Um tique: soma o uso de quem está na frente, começa ou termina bloqueios, ejeta o app bloqueado da frente e atualiza a notificação. */
    private fun avancar() {
        if (!ligado) return
        val agora = System.currentTimeMillis()
        val passou = if (ultimoTique == 0L) 0L else agora - ultimoTique
        ultimoTique = agora
        if (uso == null && Uso.liberado(this)) uso = Uso(this)
        val naFrente = if (telaLigada()) uso?.naFrente() else null
        val uidNaFrente = naFrente?.let { p -> uids.getOrPut(p) { runCatching { packageManager.getPackageUid(p, 0) }.getOrNull() } }
        val emUso = siteEmUso(naFrente, uidNaFrente, navegadores, ultimaConsulta, agora)
        // Uma rodada só: o tempo do Instagram e do YouTube soma junto, e o bloqueio vale pros dois ([Estado.rodada])
        val antes = Estado.rodada.value
        val depois = antes.avancar(agora, if (emUso != null) passou else 0L)
        val comecou = !antes.bloqueada(agora) && depois.bloqueada(agora)
        val virou = comecou || (antes.bloqueadoAte != 0L && depois.bloqueadoAte == 0L)
        val gravar = virou || agora - gravadoEm >= GRAVAR_MS
        Estado.atualizar(depois, gravar)
        if (gravar) gravadoEm = agora
        if (comecou) Log.i(TAG, "Escudo: 7 min usados (Instagram + YouTube), os dois bloqueados por ${BLOQUEIO_MS / 60_000} min")
        // Bloqueado e um dos dois na frente (o app, ou um navegador no site): a tela de bloqueio aparece por cima dele
        val ejetar = emUso?.takeIf { depois.bloqueada(agora) }
        if (ejetar != null) {
            medidor.esconder()
            cobertura.mostrar(ejetar, depois.bloqueadoAte)
            if (naFrente != avisadoNaFrente) Notificacoes.avisarBloqueio(this, ejetar, depois.bloqueadoAte)
            avisadoNaFrente = naFrente
        } else {
            cobertura.esconder()
            avisadoNaFrente = null
            // Livre, com o Instagram ou o YouTube na frente: a plaquinha do tempo (igual à do Maverick do computador)
            if (emUso != null) medidor.mostrar(emUso, depois.usadoMs, LIVRE_MS) else medidor.esconder()
        }
        atualizarNotificacao(agora, depois, forcar = virou)
    }

    /** O túnel que só observa o DNS: a única rota é o servidor de DNS de mentira dentro dele. Montado uma vez; só volta se a leitura cair. */
    private fun montarTunel() {
        val b = Builder()
            .setSession("Maverick Truck")
            .setBlocking(true)
            .setMtu(1500)
            .addAddress(ENDERECO, 32)
            .addDnsServer(DNS)
            .addRoute(DNS, 32)
            // O IPv6 (e o resto do IPv4) dos apps segue direto pela rede: o escudo só olha o DNS, não corta nada
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
            .setMetered(false)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        b.addDisallowedApplication(packageName)
        for (pacote in FORA) runCatching { b.addDisallowedApplication(pacote) } // não instalado: nada a tirar
        val novo = runCatching { b.establish() }.onFailure { Log.w(TAG, "Escudo: o Android não montou o túnel", it) }.getOrNull()
        if (novo == null) {
            if (prepare(this) != null) {
                Estado.ligar(false)
                Notificacoes.avisarReligar(this)
                parar()
            }
            return
        }
        val velho = tunel
        synchronized(travaDaSaida) {
            tunel = novo
            saida = FileOutputStream(novo.fileDescriptor)
        }
        ler(novo)
        velho?.let { runCatching { it.close() } }
    }

    /**
     * A leitura do túnel [fd], numa linha de execução só dele, até ele ser trocado ou fechado. Só chegam perguntas de
     * DNS (a única rota do túnel). Se a leitura cair com o túnel de pé, ninguém mais leria o DNS: o túnel é remontado.
     */
    private fun ler(fd: ParcelFileDescriptor) {
        thread(name = "escudo", isDaemon = true) {
            val entrada = FileInputStream(fd.fileDescriptor)
            val pacote = ByteArray(32_767)
            val espera = arrayOf(StructPollfd().apply {
                this.fd = fd.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            })
            while (tunel === fd) {
                val prontos = try {
                    Os.poll(espera, 500)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else break
                }
                if (prontos <= 0) continue
                if (espera[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                val n = try {
                    entrada.read(pacote)
                } catch (e: IOException) {
                    break
                }
                if (n <= 0) continue
                val pergunta = lerPerguntaDns(pacote, n) ?: continue
                responder(pergunta, SystemClock.elapsedRealtime())
            }
            if (tunel === fd) handler.post { leituraCaiu(fd) }
        }
    }

    /** A leitura do túnel [fd] parou com ele de pé: o túnel é montado de novo, no máximo [REMONTAGENS_MAX] vezes por minuto. */
    private fun leituraCaiu(fd: ParcelFileDescriptor) {
        if (tunel !== fd || !ligado) return
        val agora = SystemClock.elapsedRealtime()
        while (remontagens.isNotEmpty() && agora - remontagens.first() > 60_000L) remontagens.removeFirst()
        if (remontagens.size >= REMONTAGENS_MAX) {
            Log.e(TAG, "Escudo: a leitura do túnel caiu $REMONTAGENS_MAX vezes num minuto, o escudo desliga")
            Estado.ligar(false)
            Notificacoes.avisarReligar(this)
            return parar()
        }
        remontagens.addLast(agora)
        Log.w(TAG, "Escudo: a leitura do túnel caiu, montando de novo")
        montarTunel()
    }

    /**
     * Uma pergunta de DNS (lida em [lidaEm], elapsedRealtime): o escudo não bloqueia nada, só anota se é de um site
     * regrado (pra saber que um navegador está nele) e manda a resposta de verdade de volta, com validade curta se é de
     * um site regrado ([TTL_REGRADO_S]), pra o navegador perguntar de novo e o escudo saber que ele continua lá.
     */
    private fun responder(p: PerguntaDns, lidaEm: Long) {
        val (nome, _) = nomeDaPergunta(p.dns) ?: return
        val agora = System.currentTimeMillis()
        val site = siteDoDominio(nome)
        if (site != null) anotar(site, p, agora)
        runCatching {
            consultas.execute {
                // Esperou demais na fila (a rede trocou e o DNS demorou): o Android já perguntou de novo
                if (SystemClock.elapsedRealtime() - lidaEm > PERGUNTA_VELHA_MS) return@execute
                val resposta = perguntarLaFora(p.dns) ?: return@execute
                val final = if (site != null) encurtarTtl(resposta, TTL_REGRADO_S) else resposta
                escrever(pacoteUdp(p.destino, p.origem, p.portaDestino, p.portaOrigem, final))
            }
        }
    }

    /**
     * O site foi pedido ao DNS agora, por quem ([Consulta.uid]): com esse navegador na frente, conta como o site aberto
     * nele ([siteEmUso]). O pedido de outro app (o próprio Instagram em segundo plano, o YouTube Music) não conta.
     */
    private fun anotar(site: Site, p: PerguntaDns, agora: Long) {
        val uid = runCatching {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(p.origem), p.portaOrigem),
                InetSocketAddress(InetAddress.getByAddress(p.destino), p.portaDestino),
            )
        }.getOrDefault(Process.INVALID_UID)
        if (uid !in uidsDosApps) ultimaConsulta[site] = Consulta(agora, uid)
    }

    /**
     * A pergunta pro DNS de verdade da rede (por fora do túnel); null se nenhum respondeu a tempo. O que respondeu vai
     * pra frente da lista; um que não respondeu faz a lista ser relida na próxima (a rede pode ter mudado).
     */
    private fun perguntarLaFora(dns: ByteArray): ByteArray? {
        for (servidor in servidoresDeVerdade()) {
            val resposta = runCatching {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = ESPERA_DNS_MS
                    s.send(DatagramPacket(dns, dns.size, servidor, 53))
                    val buf = ByteArray(8192)
                    val recebido = DatagramPacket(buf, buf.size)
                    s.receive(recebido)
                    buf.copyOf(recebido.length)
                }
            }.getOrNull()
            // A mesma identificação da pergunta: a resposta é dela
            if (resposta != null && resposta.size >= 12 && resposta[0] == dns[0] && resposta[1] == dns[1]) {
                val lista = servidores
                if (lista.firstOrNull() != servidor && servidor in lista) servidores = listOf(servidor) + (lista - servidor)
                return resposta
            }
            servidoresEm = 0L
        }
        return null
    }

    /** Os servidores de DNS da rede de verdade (o app está fora do túnel, então a rede dele é a de verdade), e os de reserva. */
    private fun servidoresDeVerdade(): List<InetAddress> {
        val agora = SystemClock.elapsedRealtime()
        if (servidores.isNotEmpty() && agora - servidoresEm < RELER_SERVIDORES_MS) return servidores
        val cm = getSystemService(ConnectivityManager::class.java)
        val daRede = runCatching { cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty() }.getOrDefault(emptyList())
            .filter { it.hostAddress != DNS }
        servidores = (daRede + RESERVA.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }).distinct()
        servidoresEm = agora
        return servidores
    }

    /** Um pacote pro túnel de agora (uma resposta que chega depois de uma troca de túnel vai pro novo, não se perde). */
    private fun escrever(pacote: ByteArray) {
        synchronized(travaDaSaida) { runCatching { saida?.write(pacote) } }
    }

    /** A notificação fixa com a rodada de cada site; no máximo a cada [NOTIFICAR_MS], ou na hora quando um bloqueio começa ou acaba. */
    private fun atualizarNotificacao(agora: Long, rodada: Rodada, forcar: Boolean) {
        if (!forcar && agora - notificadoEm < NOTIFICAR_MS) return
        val texto = if (rodada.bloqueada(agora)) {
            "Instagram & YouTube blocked · ${mmss(rodada.restanteMs(agora))} left"
        } else {
            "Instagram & YouTube · ${mmss(rodada.usadoMs)} of ${mmss(LIVRE_MS)} used"
        }
        if (texto == ultimoTexto && !forcar) return
        ultimoTexto = texto
        notificadoEm = agora
        Notificacoes.mostrarEstado(this, texto)
    }

    /** Fecha o túnel e o serviço (o estado ligado ou não já foi gravado por quem chamou). */
    private fun parar() {
        handler.removeCallbacks(tique)
        if (ligado) {
            runCatching { unregisterReceiver(tela) }
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(rede) }
        }
        ligado = false
        rodando = false
        cobertura.esconder()
        medidor.esconder()
        val t = tunel
        synchronized(travaDaSaida) {
            tunel = null
            saida = null
        }
        t?.let { runCatching { it.close() } }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** O Android tirou a VPN: outra VPN ligou, ou ela foi desligada nas Configurações. O escudo cai; a notificação pede pra religar. */
    override fun onRevoke() {
        // Chega numa linha de execução do Android: o resto do escudo vive na principal
        handler.post {
            Log.w(TAG, "Escudo: o Android tirou a VPN")
            Estado.ligar(false)
            Notificacoes.avisarReligar(this)
            parar()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tique)
        if (ligado) {
            runCatching { unregisterReceiver(tela) }
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(rede) }
        }
        ligado = false
        rodando = false
        cobertura.liberar()
        medidor.esconder()
        tunel?.let { runCatching { it.close() } }
        tunel = null
        consultas.shutdownNow()
        super.onDestroy()
    }
}
