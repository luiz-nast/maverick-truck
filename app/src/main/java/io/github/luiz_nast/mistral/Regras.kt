// As regras do Mistral, puras (sem Android), com testes em RegrasTest: os sites regrados, a rodada de cada um
// (7 min de uso, depois 15 min bloqueado pelo relógio), quando um momento conta como uso e o que a manhã do Tourbillon muda.
package io.github.luiz_nast.mistral

/** Uso liberado por rodada; passou disso, o site fica bloqueado por [BLOQUEIO_MS] (o dono, 10-30: "7 min liberados, 15 bloqueados"). */
const val LIVRE_MS = 7 * 60_000L

/** O bloqueio depois da rodada, pelo relógio: conta com o celular no bolso também. */
const val BLOQUEIO_MS = 15 * 60_000L

/** Um tique de uso vale no máximo isto: o celular dormindo no meio não vira minutos de uso. */
const val TIQUE_MAX_MS = 2_000L

/**
 * Um navegador na frente conta como uso de um site se o site foi pedido ao DNS por ele há menos disto. As respostas
 * dos sites regrados vão com validade curta ([TTL_REGRADO_S] = 10 s), então o navegador pergunta de novo a cada poucos
 * segundos enquanto o site está aberto. A janela é curta (um pouco acima do TTL) de propósito: se ele sair do site pra
 * outra página — um banco, por exemplo —, a tela de bloqueio e a plaquinha somem de cima dela em poucos segundos, não
 * em um minuto (revisão de 10-31: 60 s deixava a tela por cima de um banco por tempo demais).
 */
const val SITE_RECENTE_MS = 15_000L

/** Validade (em segundos) das respostas de DNS dos sites regrados: o celular pergunta de novo logo, e um bloqueio pega rápido. */
const val TTL_REGRADO_S = 10

/**
 * Os sites regrados: os [pacotes] dos apps deles e os [dominios] (com os subdomínios) pelos quais o escudo reconhece,
 * no DNS, que um navegador está no site. O escudo não corta nada: só percebe e ejeta (a tela de bloqueio por cima).
 */
enum class Site(val nome: String, val pacotes: Set<String>, val dominios: List<String>) {
    INSTAGRAM(
        "Instagram",
        setOf("com.instagram.android", "com.instagram.lite"),
        listOf("instagram.com", "cdninstagram.com", "instagr.am", "ig.me"),
    ),
    YOUTUBE(
        "YouTube",
        setOf("com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube"),
        listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com", "ytimg.com", "googlevideo.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "yt3.ggpht.com", "yt4.ggpht.com",
        ),
    ),
}

/** O nome como o DNS manda: minúsculo e sem o ponto do fim. */
private fun normalizar(host: String) = host.trim().trimEnd('.').lowercase()

/** [host] é [dominio] ou um subdomínio dele. */
private fun dentroDe(host: String, dominio: String) = host == dominio || host.endsWith(".$dominio")

/** As fotos e vídeos do Instagram também vêm de "instagram.<cidade>.fbcdn.net"; os do Facebook, do mesmo fbcdn.net, não. */
private fun cdnDoInstagram(host: String) = host.startsWith("instagram.") && dentroDe(host, "fbcdn.net")

/** De qual site regrado é [host]; null se de nenhum. */
fun siteDoDominio(host: String): Site? {
    val h = normalizar(host)
    if (h.isEmpty()) return null
    if (cdnDoInstagram(h)) return Site.INSTAGRAM
    return Site.entries.firstOrNull { site -> site.dominios.any { dentroDe(h, it) } }
}

/**
 * A rodada de um site: quanto já foi [usadoMs] nela e, passado o [LIVRE_MS], até quando (epoch, ms) ele fica
 * [bloqueadoAte]. 0 = livre.
 */
data class Rodada(val usadoMs: Long = 0L, val bloqueadoAte: Long = 0L) {
    fun bloqueada(agora: Long): Boolean = agora < bloqueadoAte

    /** Quanto falta: do bloqueio, se bloqueada; senão, do uso liberado. */
    fun restanteMs(agora: Long): Long = if (bloqueada(agora)) bloqueadoAte - agora else (LIVRE_MS - usadoMs).coerceAtLeast(0)

    /**
     * A rodada [emUsoMs] depois, em [agora]: bloqueada, segue (o bloqueio é pelo relógio); o bloqueio acabou, rodada nova
     * do zero; livre, soma o uso (no máximo [TIQUE_MAX_MS] por vez) e, chegando a [LIVRE_MS], bloqueia por [BLOQUEIO_MS].
     * O relógio do celular atrasado no meio de um bloqueio não o estica além de [BLOQUEIO_MS].
     */
    fun avancar(agora: Long, emUsoMs: Long): Rodada {
        if (bloqueada(agora)) return if (bloqueadoAte - agora > BLOQUEIO_MS) copy(bloqueadoAte = agora + BLOQUEIO_MS) else this
        if (bloqueadoAte != 0L) return Rodada()
        val usado = usadoMs + emUsoMs.coerceIn(0L, TIQUE_MAX_MS)
        return if (usado >= LIVRE_MS) Rodada(LIVRE_MS, agora + BLOQUEIO_MS) else copy(usadoMs = usado)
    }
}

/**
 * A etapa da atividade da manhã do Tourbillon Alarm, que manda nos sites enquanto dura (o dono, 11-08): na caminhada o
 * Instagram fica [livre], na câmera o YouTube, na conversa nenhum. O outro site fica trancado (a tela de bloqueio).
 */
enum class Etapa(val livre: Site?, val texto: String) {
    CONVERSA(null, "Instagram & YouTube locked"),
    CAMINHADA(Site.INSTAGRAM, "Instagram free, YouTube locked"),
    CAMERA(Site.YOUTUBE, "YouTube free, Instagram locked"),
}

/** O "vale até" do Tourbillon mais longe que isto no futuro é erro ou relógio mexido: não vale. */
const val VALE_ATE_MAX_MS = 5 * 60_000L

/**
 * A etapa que vale [agora], do que o Tourbillon respondeu ([nome] e [valeAte], epoch). Falha fechada: sem resposta,
 * "nenhuma", etapa desconhecida, [valeAte] vencido ou absurdo ([VALE_ATE_MAX_MS]) dão null, as regras de sempre.
 */
fun etapaQueVale(nome: String?, valeAte: Long, agora: Long): Etapa? {
    if (valeAte <= agora || valeAte - agora > VALE_ATE_MAX_MS) return null
    return Etapa.entries.firstOrNull { it.name == nome }
}

/**
 * Um tique das regras: a rodada [antes] depois de [passou] ms com [emUso] na frente, e o site a ejetar (null: nenhum).
 * Na manhã do Tourbillon ([etapa]) o uso não conta (o ciclo segue de onde estava quando ela acaba) e todo site menos o
 * livre dela é ejetado; fora dela, só com a rodada bloqueada.
 */
fun decidir(antes: Rodada, emUso: Site?, etapa: Etapa?, agora: Long, passou: Long): Pair<Rodada, Site?> {
    val depois = antes.avancar(agora, if (emUso != null && etapa == null) passou else 0L)
    val ejetar = emUso?.takeIf { if (etapa != null) it != etapa.livre else depois.bloqueada(agora) }
    return depois to ejetar
}

/** Os uids abaixo disto são do sistema (o do DNS do Android, por exemplo): quem pediu de verdade não dá pra saber. */
const val PRIMEIRO_UID_DE_APP = 10_000

/** A última pergunta de DNS de um site: [quando] (epoch) e o [uid] de quem perguntou (-1 se o Android não disse). */
data class Consulta(val quando: Long, val uid: Int)

/**
 * O site em uso agora, com a tela ligada: o app dele na frente ([naFrente], o pacote) ou um navegador na frente
 * ([navegadores], com o [uidNaFrente] dele) que pediu o site ao DNS há menos de [SITE_RECENTE_MS] ([ultimaConsulta]).
 * A pergunta de outro app (o YouTube Music tocando, um link no WhatsApp) não conta; a de quem o Android não diz quem
 * é ([PRIMEIRO_UID_DE_APP]), conta.
 *
 * Depois disso o site continua em uso enquanto o mesmo navegador fica na frente sem parar (desde [naFrenteDesde]) e não
 * pede ao DNS nenhum outro site ([outraConsulta], o último pedido dele que não é do site nem de apoio, [deApoio]): um
 * vídeo no YouTube do navegador não pergunta mais nada ao DNS e antes parava de contar em 15 s (o dono, 10-31: 8 min no
 * site viraram 1:47). Sair do navegador ou abrir outro site (um banco) termina na hora. Null: nenhum.
 */
fun siteEmUso(
    naFrente: String?,
    uidNaFrente: Int?,
    navegadores: Set<String>,
    ultimaConsulta: Map<Site, Consulta>,
    agora: Long,
    naFrenteDesde: Long = Long.MAX_VALUE,
    outraConsulta: Consulta? = null,
): Site? {
    if (naFrente == null) return null
    Site.entries.firstOrNull { naFrente in it.pacotes }?.let { return it }
    if (naFrente !in navegadores) return null
    val (site, c) = ultimaConsulta.entries
        .filter { (_, c) -> c.quando <= agora && (c.uid == uidNaFrente || c.uid < PRIMEIRO_UID_DE_APP) }
        .maxByOrNull { it.value.quando }?.toPair() ?: return null
    if (agora - c.quando <= SITE_RECENTE_MS) return site
    val continuouNaFrente = c.quando >= naFrenteDesde
    val foiPraOutro = outraConsulta != null && outraConsulta.uid == uidNaFrente && outraConsulta.quando > c.quando
    return site.takeIf { continuouNaFrente && !foiPraOutro }
}

/**
 * Nomes que uma página do YouTube ou do Instagram pede junto (anúncio, fonte, conta Google, o Facebook por trás do
 * Instagram) e o próprio navegador pede sozinho: não querem dizer que a pessoa foi pra outro site.
 */
private val DE_APOIO = listOf(
    "google.com", "gstatic.com", "googleapis.com", "googleusercontent.com", "ggpht.com", "gvt1.com", "gvt2.com",
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com", "googletagmanager.com",
    "facebook.com", "facebook.net", "fbcdn.net", "fbsbx.com", "whatsapp.net", "samsung.com", "samsungapps.com",
    "mozilla.com", "mozilla.net", "mozilla.org", "brave.com", "bravesoftware.com", "microsoft.com", "bing.com",
)

/** [host] é de apoio ([DE_APOIO]): pedir não tira ninguém do site regrado. */
fun deApoio(host: String): Boolean {
    val h = normalizar(host)
    return DE_APOIO.any { dentroDe(h, it) }
}

/** "6:59", "15:00": minutos e segundos, arredondando o segundo pra cima (o 0:00 só no fim). */
fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
