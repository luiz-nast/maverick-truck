// As regras do Maverick Truck, puras (sem Android), com testes em RegrasTest: os sites regrados, a rodada de cada um
// (7 min de uso, depois 15 min bloqueado pelo relógio) e quando um momento conta como uso.
package io.github.luiz_nast.maverick.truck

/** Uso liberado por rodada; passou disso, o site fica bloqueado por [BLOQUEIO_MS] (o dono, 10-30: "7 min liberados, 15 bloqueados"). */
const val LIVRE_MS = 7 * 60_000L

/** O bloqueio depois da rodada, pelo relógio: conta com o celular no bolso também. */
const val BLOQUEIO_MS = 15 * 60_000L

/** Um tique de uso vale no máximo isto: o celular dormindo no meio não vira minutos de uso. */
const val TIQUE_MAX_MS = 2_000L

/**
 * Um navegador na frente conta como uso de um site se o site foi pedido ao DNS por ele há menos disto. As respostas
 * dos sites regrados vão com validade curta ([TTL_REGRADO_S]), então o navegador pergunta de novo a cada poucos
 * segundos enquanto o site está aberto; a margem cobre o cache do próprio navegador e a conexão reaproveitada.
 */
const val SITE_RECENTE_MS = 60_000L

/** Validade (em segundos) das respostas de DNS dos sites regrados: o celular pergunta de novo logo, e um bloqueio pega rápido. */
const val TTL_REGRADO_S = 10

/**
 * Os sites regrados: os [pacotes] dos apps deles e os [dominios] (com os subdomínios) que o escudo recusa no bloqueio.
 * Dos [dominiosDeIp], o escudo também guarda os endereços que o DNS respondeu, pra cortar no bloqueio as conexões que
 * já estavam abertas: só os que servem a esse site e nada mais (os servidores de vídeo do YouTube, os do Instagram).
 * Os do Google em geral (youtube.com, ytimg.com) ficam de fora: servem também a busca e o Gmail.
 */
enum class Site(val nome: String, val pacotes: Set<String>, val dominios: List<String>, val dominiosDeIp: List<String>) {
    INSTAGRAM(
        "Instagram",
        setOf("com.instagram.android", "com.instagram.lite"),
        listOf("instagram.com", "cdninstagram.com", "instagr.am", "ig.me"),
        listOf("instagram.com", "cdninstagram.com"),
    ),
    YOUTUBE(
        "YouTube",
        setOf("com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube"),
        listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com", "ytimg.com", "googlevideo.com",
            "youtubei.googleapis.com", "youtube.googleapis.com", "yt3.ggpht.com", "yt4.ggpht.com",
        ),
        listOf("googlevideo.com"),
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
 * Os servidores de vídeo do YouTube ("rr3---sn-oxu8pnpvo-ua8l.googlevideo.com"). Os outros nomes de googlevideo.com
 * (manifest, redirector) moram nos servidores gerais do Google, que servem também a Play Store: esses não (revisão de 10-31).
 */
private val VIDEO_DO_YOUTUBE = Regex("^rr\\d+(---|\\.)sn-[a-z0-9-]+\\.googlevideo\\.com$")

/** Os endereços que o DNS responder pra [host] servem só ao site dele (e o escudo pode cortá-los no bloqueio)? */
fun guardaEnderecos(host: String): Boolean {
    val h = normalizar(host)
    if (cdnDoInstagram(h)) return true
    if (dentroDe(h, "googlevideo.com")) return VIDEO_DO_YOUTUBE.matches(h)
    val site = siteDoDominio(h) ?: return false
    return site.dominiosDeIp.any { dentroDe(h, it) }
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

/** Os uids abaixo disto são do sistema (o do DNS do Android, por exemplo): quem pediu de verdade não dá pra saber. */
const val PRIMEIRO_UID_DE_APP = 10_000

/** A última pergunta de DNS de um site: [quando] (epoch) e o [uid] de quem perguntou (-1 se o Android não disse). */
data class Consulta(val quando: Long, val uid: Int)

/**
 * O site em uso agora, com a tela ligada: o app dele na frente ([naFrente], o pacote) ou um navegador na frente
 * ([navegadores], com o [uidNaFrente] dele) que pediu o site ao DNS há menos de [SITE_RECENTE_MS] ([ultimaConsulta]).
 * A pergunta de outro app (o YouTube Music tocando, um link no WhatsApp) não conta; a de quem o Android não diz quem
 * é ([PRIMEIRO_UID_DE_APP]), conta. Null: nenhum.
 */
fun siteEmUso(naFrente: String?, uidNaFrente: Int?, navegadores: Set<String>, ultimaConsulta: Map<Site, Consulta>, agora: Long): Site? {
    if (naFrente == null) return null
    Site.entries.firstOrNull { naFrente in it.pacotes }?.let { return it }
    if (naFrente !in navegadores) return null
    return ultimaConsulta
        .filter { (_, c) -> agora - c.quando in 0..SITE_RECENTE_MS && (c.uid == uidNaFrente || c.uid < PRIMEIRO_UID_DE_APP) }
        .maxByOrNull { it.value.quando }?.key
}

/** "6:59", "15:00": minutos e segundos, arredondando o segundo pra cima (o 0:00 só no fim). */
fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
