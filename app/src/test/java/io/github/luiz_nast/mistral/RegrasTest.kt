package io.github.luiz_nast.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasTest {

    @Test
    fun dominiosDoInstagramEDoYoutube() {
        assertEquals(Site.INSTAGRAM, siteDoDominio("www.instagram.com"))
        assertEquals(Site.INSTAGRAM, siteDoDominio("i.instagram.com."))
        assertEquals(Site.INSTAGRAM, siteDoDominio("scontent-gru2-1.cdninstagram.com"))
        assertEquals(Site.INSTAGRAM, siteDoDominio("instagram.fbsb1-1.fna.fbcdn.net"))
        assertEquals(Site.YOUTUBE, siteDoDominio("M.YouTube.com"))
        assertEquals(Site.YOUTUBE, siteDoDominio("rr3---sn-oxu8pnpvo-ua8l.googlevideo.com"))
        assertEquals(Site.YOUTUBE, siteDoDominio("youtubei.googleapis.com"))
        assertEquals(Site.YOUTUBE, siteDoDominio("i.ytimg.com"))
        // O resto do Google e do Facebook, o WhatsApp, e nomes parecidos não
        assertNull(siteDoDominio("www.google.com"))
        assertNull(siteDoDominio("gmail.googleapis.com"))
        assertNull(siteDoDominio("scontent.fbsb1-1.fna.fbcdn.net"))
        assertNull(siteDoDominio("mmg.whatsapp.net"))
        assertNull(siteDoDominio("notyoutube.com"))
        assertNull(siteDoDominio("instagram.com.golpe.net"))
        assertNull(siteDoDominio(""))
    }

    @Test
    fun seteMinutosDeUsoEQuinzeBloqueado() {
        var r = Rodada()
        var agora = 1_000_000L
        // 7 min de uso, de segundo em segundo
        repeat((LIVRE_MS / 1_000).toInt() - 1) {
            agora += 1_000
            r = r.avancar(agora, 1_000)
        }
        assertFalse(r.bloqueada(agora))
        assertEquals(1_000L, r.restanteMs(agora))
        agora += 1_000
        r = r.avancar(agora, 1_000)
        assertTrue(r.bloqueada(agora))
        assertEquals(BLOQUEIO_MS, r.restanteMs(agora))
        // No bloqueio, o uso não muda nada; ele acaba pelo relógio
        r = r.avancar(agora + 60_000, 1_000)
        assertEquals(BLOQUEIO_MS - 60_000, r.restanteMs(agora + 60_000))
        val fim = agora + BLOQUEIO_MS
        assertTrue(r.avancar(fim - 1, 0).bloqueada(fim - 1))
        assertEquals(Rodada(), r.avancar(fim, 0))
    }

    @Test
    fun oCelularDormindoNaoViraUso() {
        val r = Rodada().avancar(10_000, 5 * 60_000)
        assertEquals(TIQUE_MAX_MS, r.usadoMs)
    }

    @Test
    fun relogioAtrasadoNaoEsticaOBloqueio() {
        val r = Rodada(LIVRE_MS, 2_000_000L + BLOQUEIO_MS)
        // O relógio voltou 1 h no meio do bloqueio: ele fica em no máximo 15 min a partir de agora
        val agora = 2_000_000L - 3_600_000L
        assertEquals(BLOQUEIO_MS, r.avancar(agora, 0).restanteMs(agora))
    }

    @Test
    fun oQueContaComoUso() {
        val navegadores = setOf("com.android.chrome", "com.sec.android.app.sbrowser")
        val chrome = 10_123
        val agora = 5_000_000L
        val uso = { naFrente: String?, consultas: Map<Site, Consulta> -> siteEmUso(naFrente, chrome, navegadores, consultas, agora) }
        assertEquals(Site.INSTAGRAM, siteEmUso("com.instagram.android", 10_200, navegadores, emptyMap(), agora))
        assertEquals(Site.YOUTUBE, siteEmUso("com.google.android.youtube", 10_201, navegadores, emptyMap(), agora))
        // No navegador, só com o site pedido ao DNS por ele há pouco; o mais recente vale
        assertEquals(Site.YOUTUBE, uso("com.android.chrome", mapOf(Site.YOUTUBE to Consulta(agora - 5_000, chrome))))
        assertEquals(
            Site.INSTAGRAM,
            uso("com.android.chrome", mapOf(Site.YOUTUBE to Consulta(agora - 9_000, chrome), Site.INSTAGRAM to Consulta(agora - 1_000, chrome))),
        )
        assertNull(uso("com.android.chrome", mapOf(Site.YOUTUBE to Consulta(agora - SITE_RECENTE_MS - 1, chrome))))
        // Perguntado por outro app (o YouTube Music tocando) com o Chrome na frente: não; sem dono conhecido (o DNS do Android): sim
        assertNull(uso("com.android.chrome", mapOf(Site.YOUTUBE to Consulta(agora - 1_000, 10_555))))
        assertEquals(Site.YOUTUBE, uso("com.android.chrome", mapOf(Site.YOUTUBE to Consulta(agora - 1_000, -1))))
        assertEquals(Site.YOUTUBE, uso("com.android.chrome", mapOf(Site.YOUTUBE to Consulta(agora - 1_000, 1051))))
        // Outro app na frente, com o site pedido há pouco: não
        assertNull(uso("com.whatsapp", mapOf(Site.INSTAGRAM to Consulta(agora, chrome))))
        assertNull(uso(null, mapOf(Site.INSTAGRAM to Consulta(agora, chrome))))
    }

    @Test
    fun oSiteNoNavegadorContaEnquantoONavegadorFicaNaFrente() {
        val agora = 1_000_000L
        val chrome = 10_150
        val nav = setOf("com.android.chrome")
        val yt = mapOf(Site.YOUTUBE to Consulta(agora - 8 * 60_000, chrome)) // pediu o YouTube há 8 min, vídeo rodando
        val desde = agora - 9 * 60_000 // o Chrome está na frente desde antes
        assertEquals(Site.YOUTUBE, siteEmUso("com.android.chrome", chrome, nav, yt, agora, desde, null))
        // Pedidos de apoio (anúncio, conta Google) não tiram do site
        assertTrue(deApoio("pagead2.googlesyndication.com"))
        assertTrue(deApoio("accounts.google.com"))
        assertFalse(deApoio("nubank.com.br"))
        // Pediu outro site depois: saiu
        val banco = Consulta(agora - 60_000, chrome)
        assertNull(siteEmUso("com.android.chrome", chrome, nav, yt, agora, desde, banco))
        // Outro app pedindo outro site não muda nada
        assertEquals(Site.YOUTUBE, siteEmUso("com.android.chrome", chrome, nav, yt, agora, desde, Consulta(agora - 60_000, 10_999)))
        // Saiu do navegador e voltou depois do pedido: só volta a contar quando o site for pedido de novo
        assertNull(siteEmUso("com.android.chrome", chrome, nav, yt, agora, agora - 60_000, null))
    }

    @Test
    fun aMusicaComecaEm35PorCento() {
        // Volume de mídia do S24 FE: 15 degraus. 35% = 5,25 → 5
        assertEquals(5, volumeDaMusica(15))
        assertEquals(9, volumeDaMusica(25))
        assertEquals(1, volumeDaMusica(1))
    }

    @Test
    fun oSorteioNaoRepeteOSomAnterior() {
        assertNull(sortearSom(emptyList(), null))
        assertEquals("a.mp3", sortearSom(listOf("a.mp3"), "a.mp3"))
        val nomes = listOf("a.mp3", "b.mp3", "c.mp3")
        repeat(200) { semente -> assertNotEquals("b.mp3", sortearSom(nomes, "b.mp3", kotlin.random.Random(semente))) }
        val sorteados = (0 until 200).map { sortearSom(nomes, null, kotlin.random.Random(it)) }.toSet()
        assertEquals(nomes.toSet(), sorteados)
    }

    @Test
    fun relogioDaTela() {
        assertEquals("7:00", mmss(LIVRE_MS))
        assertEquals("0:01", mmss(1))
        assertEquals("0:00", mmss(0))
        assertEquals("14:59", mmss(BLOQUEIO_MS - 1_000))
    }
}
