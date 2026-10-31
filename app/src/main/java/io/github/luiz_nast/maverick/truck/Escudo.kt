// O escudo: um serviço de VPN que fica no próprio celular (nada sai por um servidor de fora). Ele faz duas coisas:
//
// 1. Conta o uso. A cada segundo de tela ligada, vê qual app está na frente (Uso) e, se é o Instagram ou o YouTube (o
//    app, ou um navegador com o site aberto), soma na rodada do site. Passou de 7 min, o site fica bloqueado 15 min
//    pelo relógio (Regras: Rodada).
// 2. Bloqueia. O túnel só recebe as perguntas de DNS de todos os apps (o "endereço de quem é instagram.com?"): as dos
//    sites bloqueados levam "não existe" na hora, e as outras vão pro DNS de verdade da rede, pelo próprio app. No
//    bloqueio, o túnel também puxa os endereços dos servidores do site que o DNS já respondeu antes (os de vídeo do
//    YouTube, os do Instagram): o que chega neles cai aqui e é descartado, então até a conexão que já estava aberta
//    para de funcionar.
//
// Ficam de fora do túnel o próprio app (é por ele que o DNS de verdade sai), o Nubank, outros bancos e o Nightmare
// Alarm: nada deles passa por aqui. Sem serviço de acessibilidade, pelo mesmo motivo: o Nubank trava o celular quando
// um app de fora da Play Store tem um ligado.
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Tag dos logs do app: `adb logcat -s Maverick`. */
const val TAG = "Maverick"

class Escudo : VpnService() {

    companion object {
        private const val ACAO_LIGAR = "ligar"
        private const val ACAO_DESLIGAR = "desligar"

        /** O endereço do celular dentro do túnel e o "servidor de DNS" que só existe nele. */
        private const val ENDERECO = "10.215.173.1"
        private const val ENDERECO_V6 = "fdc3:5ee2:a17e::1"
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

        /** Linhas de registro por túnel (o que os apps regrados perguntam e o que foi cortado): o suficiente pra entender, sem encher. */
        private const val REGISTROS_MAX = 80

        /** O escudo está rodando neste processo (o [Estado.ligado] gravado pode dizer "ligado" depois de o app ser morto). */
        @Volatile
        var rodando = false
            private set

        /** Liga o escudo (a permissão da VPN já dada: [VpnService.prepare] devolve null). */
        fun ligar(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, Escudo::class.java).setAction(ACAO_LIGAR))
        }

        /** Desliga, se nenhum site está bloqueado agora (no meio de um bloqueio, não). */
        fun desligar(ctx: Context) {
            ctx.startService(Intent(ctx, Escudo::class.java).setAction(ACAO_DESLIGAR))
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

    /** Os sites bloqueados quando o túnel de agora foi montado (lido também pela leitura do túnel). */
    @Volatile
    private var noTunel: Set<Site>? = null
    private val revisar = AtomicBoolean(false) // a leitura já pediu um tique pra ver se um bloqueio acabou
    private val remontagens = ArrayDeque<Long>()

    /** O que já foi pro registro neste túnel (cada nome perguntado e cada destino cortado, uma vez só, até [REGISTROS_MAX]). */
    private val registrados = ConcurrentHashMap.newKeySet<String>()
    private var ligado = false
    private var uso: Uso? = null
    private val cobertura by lazy { Cobertura(this) }
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

    /** O fim de um bloqueio com a tela apagada: o túnel solta os endereços cortados na hora, não só quando a tela acender. */
    private val fimDoBloqueio = Runnable { avancar() }

    /**
     * Tela apagada: o uso para de contar (o bloqueio segue pelo relógio, e o fim dele fica marcado). Acesa: volta a
     * contar, com a lista de navegadores relida (um navegador instalado depois também vale).
     */
    private val tela = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            handler.removeCallbacks(tique)
            handler.removeCallbacks(fimDoBloqueio)
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                avancar()
                cobertura.esconder()
                ultimoTique = 0L
                val agora = System.currentTimeMillis()
                Estado.rodadas.value.values.filter { it.bloqueada(agora) }.minOfOrNull { it.bloqueadoAte }
                    ?.let { handler.postDelayed(fimDoBloqueio, it - agora + 500) }
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
        if (intent?.action == ACAO_DESLIGAR) {
            if (Estado.algumBloqueado(System.currentTimeMillis())) {
                Log.i(TAG, "Escudo: no meio de um bloqueio não desliga")
                return START_STICKY // o escudo segue; morto pelo Android, ele volta
            }
            Log.i(TAG, "Escudo: desligado")
            Estado.ligar(false)
            parar()
            return START_NOT_STICKY
        }
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
            noTunel = null
            Log.i(TAG, "Escudo: ligado (${navegadores.size} navegadores)")
        }
        handler.removeCallbacks(tique)
        avancar()
        if (telaLigada()) handler.postDelayed(tique, TIQUE_MS)
    }

    private fun telaLigada() = getSystemService(PowerManager::class.java).isInteractive

    /** Um tique: soma o uso de quem está na frente, começa ou termina bloqueios, monta o túnel e atualiza a notificação. */
    private fun avancar() {
        if (!ligado) return
        val agora = System.currentTimeMillis()
        val passou = if (ultimoTique == 0L) 0L else agora - ultimoTique
        ultimoTique = agora
        if (uso == null && Uso.liberado(this)) uso = Uso(this)
        val naFrente = if (telaLigada()) uso?.naFrente() else null
        val uidNaFrente = naFrente?.let { p -> uids.getOrPut(p) { runCatching { packageManager.getPackageUid(p, 0) }.getOrNull() } }
        val emUso = siteEmUso(naFrente, uidNaFrente, navegadores, ultimaConsulta, agora)
        val antes = Estado.rodadas.value
        val depois = Site.entries.associateWith { antes.getValue(it).avancar(agora, if (it == emUso) passou else 0L) }
        val comecou = Site.entries.filter { !antes.getValue(it).bloqueada(agora) && depois.getValue(it).bloqueada(agora) }
        val acabou = Site.entries.filter { antes.getValue(it).bloqueadoAte != 0L && depois.getValue(it).bloqueadoAte == 0L }
        val virou = comecou.isNotEmpty() || acabou.isNotEmpty()
        val gravar = virou || agora - gravadoEm >= GRAVAR_MS
        Estado.atualizar(depois, gravar)
        if (gravar) gravadoEm = agora
        for (site in comecou) {
            Log.i(TAG, "Escudo: ${site.nome} usou ${LIVRE_MS / 60_000} min, bloqueado por ${BLOQUEIO_MS / 60_000} min")
            Notificacoes.avisarBloqueio(this, site, depois.getValue(site).bloqueadoAte)
            if (naFrente != null && naFrente in site.pacotes) avisadoNaFrente = naFrente
        }
        for (site in acabou) Log.i(TAG, "Escudo: ${site.nome} livre de novo")
        // Aberto no meio do bloqueio: o aviso, uma vez por abertura
        val bloqueadoNaFrente = Site.entries.firstOrNull { naFrente != null && naFrente in it.pacotes && depois.getValue(it).bloqueada(agora) }
        if (bloqueadoNaFrente != null && naFrente != avisadoNaFrente) {
            Notificacoes.avisarBloqueio(this, bloqueadoNaFrente, depois.getValue(bloqueadoNaFrente).bloqueadoAte)
        }
        avisadoNaFrente = if (bloqueadoNaFrente != null) naFrente else null
        // A tela por cima: só sobre o app do site bloqueado que está na frente (nunca sobre banco: naFrente é o pacote dele mesmo)
        if (bloqueadoNaFrente != null) cobertura.mostrar(bloqueadoNaFrente, depois.getValue(bloqueadoNaFrente).bloqueadoAte) else cobertura.esconder()
        montarTunel(agora)
        atualizarNotificacao(agora, depois, forcar = virou)
    }

    /** O túnel com os sites bloqueados de agora; só remonta quando eles mudam (o Android troca a rede da VPN por inteiro). */
    private fun montarTunel(agora: Long) {
        val bloqueados = Site.entries.filter { Estado.bloqueado(it, agora) }.toSet()
        if (tunel != null && bloqueados == noTunel) return
        val cortados = bloqueados.flatMap { Estado.enderecos(it) }
        val (v6, v4) = cortados.partition { ':' in it }
        val b = Builder()
            .setSession("Maverick Truck")
            .setBlocking(true)
            .setMtu(1500)
            .addAddress(ENDERECO, 32)
            .addDnsServer(DNS)
            .addRoute(DNS, 32)
            // O IPv6 dos apps segue pela rede de verdade, como o IPv4: sem isto, o Android corta todo o IPv6 deles
            // enquanto o escudo estiver de pé (revisão de 10-31)
            .allowFamily(OsConstants.AF_INET6)
            .setMetered(false)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (v6.isNotEmpty()) b.addAddress(ENDERECO_V6, 128)
        val rotas = v4.count { runCatching { b.addRoute(it, 32) }.isSuccess } + v6.count { runCatching { b.addRoute(it, 128) }.isSuccess }
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
        noTunel = bloqueados
        revisar.set(false)
        registrados.clear()
        ler(novo)
        velho?.let { runCatching { it.close() } }
        Log.i(TAG, "Escudo: túnel ${if (bloqueados.isEmpty()) "sem bloqueio" else "bloqueando ${bloqueados.joinToString { it.nome }}, $rotas endereços cortados"}")
        if (cortados.isNotEmpty()) Log.i(TAG, "Escudo: cortados ${cortados.joinToString(" ").take(600)}")
    }

    /**
     * A leitura do túnel [fd], numa linha de execução só dele, até ele ser trocado ou fechado. Se ela cair com o túnel
     * ainda de pé, ninguém mais leria o DNS do celular: o túnel é remontado ([leituraCaiu]).
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
                talvezOBloqueioAcabou()
                // O que não é pergunta de DNS são os pacotes pros endereços cortados no bloqueio: ficam sem resposta
                val pergunta = lerPerguntaDns(pacote, n)
                if (pergunta == null) {
                    registrarCortado(pacote, n)
                    continue
                }
                responder(pergunta, SystemClock.elapsedRealtime())
            }
            if (tunel === fd) handler.post { leituraCaiu(fd) }
        }
    }

    /**
     * Chegou algo no túnel montado com bloqueio, e o relógio diz que nenhum site está mais bloqueado (a tela apagada não
     * dá tique): um tique só, pra soltar os endereços cortados.
     */
    private fun talvezOBloqueioAcabou() {
        val bloqueados = noTunel ?: return
        if (bloqueados.isEmpty()) return
        val agora = System.currentTimeMillis()
        if (bloqueados.any { Estado.bloqueado(it, agora) }) return
        if (revisar.compareAndSet(false, true)) handler.post { avancar() }
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
        noTunel = null
        montarTunel(System.currentTimeMillis())
    }

    /**
     * Uma pergunta de DNS (lida em [lidaEm], elapsedRealtime): de um site bloqueado, "não existe" na hora; de resto, a
     * resposta do DNS de verdade, com validade curta se é de um site regrado ([TTL_REGRADO_S]).
     */
    private fun responder(p: PerguntaDns, lidaEm: Long) {
        val (nome, _) = nomeDaPergunta(p.dns) ?: return
        val agora = System.currentTimeMillis()
        val site = siteDoDominio(nome)
        if (site != null) {
            val uid = anotar(site, p, agora)
            registrar("dns $nome $uid") { "Escudo: DNS $nome de ${nomeDoUid(uid)}: ${if (Estado.bloqueado(site, agora)) "não existe" else "liberado"}" }
        }
        if (site != null && Estado.bloqueado(site, agora)) {
            respostaNaoExiste(p.dns)?.let { escrever(pacoteUdp(p.destino, p.origem, p.portaDestino, p.portaOrigem, it)) }
            return
        }
        runCatching {
            consultas.execute {
                // Esperou demais na fila (a rede trocou e o DNS demorou): o Android já perguntou de novo
                if (SystemClock.elapsedRealtime() - lidaEm > PERGUNTA_VELHA_MS) return@execute
                val resposta = perguntarLaFora(p.dns) ?: return@execute
                if (site != null && guardaEnderecos(nome)) {
                    Estado.guardarEnderecos(site, enderecosDaResposta(resposta).filter(::enderecoPublico).map(::textoDoEndereco))
                }
                val final = if (site != null) encurtarTtl(resposta, TTL_REGRADO_S) else resposta
                escrever(pacoteUdp(p.destino, p.origem, p.portaDestino, p.portaOrigem, final))
            }
        }
    }

    /**
     * O site foi pedido ao DNS agora, por quem ([Consulta.uid]): com esse navegador na frente, conta como o site aberto
     * nele ([siteEmUso]). O pedido de outro app (o próprio Instagram em segundo plano, o YouTube Music) não conta.
     */
    private fun anotar(site: Site, p: PerguntaDns, agora: Long): Int {
        val uid = runCatching {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(p.origem), p.portaOrigem),
                InetSocketAddress(InetAddress.getByAddress(p.destino), p.portaDestino),
            )
        }.getOrDefault(Process.INVALID_UID)
        if (uid !in uidsDosApps) ultimaConsulta[site] = Consulta(agora, uid)
        return uid
    }

    /** Uma linha de registro com a [chave] nova neste túnel, até [REGISTROS_MAX] (o [texto] só é montado se for registrar). */
    private fun registrar(chave: String, texto: () -> String) {
        if (registrados.size >= REGISTROS_MAX || !registrados.add(chave)) return
        Log.i(TAG, texto())
    }

    /** Um pacote que não é pergunta de DNS chegou no túnel: é pra um endereço cortado; quem mandou e pra onde, uma vez por destino. */
    private fun registrarCortado(pacote: ByteArray, n: Int) {
        val f = lerFluxo(pacote, n) ?: return
        val destino = textoDoEndereco(f.destino)
        val nome = if (f.protocolo == 6) "TCP" else "UDP"
        registrar("corte $nome $destino:${f.portaDestino}") {
            val uid = runCatching {
                getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                    f.protocolo,
                    InetSocketAddress(InetAddress.getByAddress(f.origem), f.portaOrigem),
                    InetSocketAddress(InetAddress.getByAddress(f.destino), f.portaDestino),
                )
            }.getOrDefault(Process.INVALID_UID)
            "Escudo: cortado $nome $destino:${f.portaDestino} de ${nomeDoUid(uid)}"
        }
    }

    private fun nomeDoUid(uid: Int): String =
        if (uid < 0) "?" else runCatching { packageManager.getNameForUid(uid) }.getOrNull() ?: uid.toString()

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
    private fun atualizarNotificacao(agora: Long, rodadas: Map<Site, Rodada>, forcar: Boolean) {
        if (!forcar && agora - notificadoEm < NOTIFICAR_MS) return
        val texto = Site.entries.joinToString(" · ") { site ->
            val r = rodadas.getValue(site)
            if (r.bloqueada(agora)) "${site.nome} blocked ${mmss(r.restanteMs(agora))}" else "${site.nome} ${mmss(r.usadoMs)} of ${mmss(LIVRE_MS)}"
        }
        if (texto == ultimoTexto && !forcar) return
        ultimoTexto = texto
        notificadoEm = agora
        Notificacoes.mostrarEstado(this, texto)
    }

    /** Fecha o túnel e o serviço (o estado ligado ou não já foi gravado por quem chamou). */
    private fun parar() {
        handler.removeCallbacks(tique)
        handler.removeCallbacks(fimDoBloqueio)
        if (ligado) {
            runCatching { unregisterReceiver(tela) }
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(rede) }
        }
        ligado = false
        rodando = false
        cobertura.esconder()
        val t = tunel
        synchronized(travaDaSaida) {
            tunel = null
            saida = null
        }
        noTunel = null
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
        handler.removeCallbacks(fimDoBloqueio)
        if (ligado) {
            runCatching { unregisterReceiver(tela) }
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(rede) }
        }
        ligado = false
        rodando = false
        cobertura.esconder()
        tunel?.let { runCatching { it.close() } }
        tunel = null
        consultas.shutdownNow()
        super.onDestroy()
    }
}
