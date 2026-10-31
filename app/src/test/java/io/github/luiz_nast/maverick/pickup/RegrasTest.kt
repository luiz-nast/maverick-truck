package io.github.luiz_nast.maverick.pickup

import org.junit.Assert.assertEquals
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
    fun soOsEnderecosQueServemSoAoSite() {
        assertTrue(guardaEnderecos("i.instagram.com"))
        assertTrue(guardaEnderecos("scontent-gru2-1.cdninstagram.com"))
        assertTrue(guardaEnderecos("instagram.fbsb1-1.fna.fbcdn.net"))
        assertTrue(guardaEnderecos("rr3---sn-oxu8pnpvo-ua8l.googlevideo.com"))
        // Os do Google em geral servem também a busca e o Gmail: o bloqueio deles é só pelo DNS
        assertFalse(guardaEnderecos("www.youtube.com"))
        assertFalse(guardaEnderecos("i.ytimg.com"))
        assertFalse(guardaEnderecos("youtubei.googleapis.com"))
        assertFalse(guardaEnderecos("www.google.com"))
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
        val agora = 5_000_000L
        assertEquals(Site.INSTAGRAM, siteEmUso("com.instagram.android", navegadores, emptyMap(), agora))
        assertEquals(Site.YOUTUBE, siteEmUso("com.google.android.youtube", navegadores, emptyMap(), agora))
        // No navegador, só com o site pedido ao DNS há pouco; o mais recente vale
        assertEquals(Site.YOUTUBE, siteEmUso("com.android.chrome", navegadores, mapOf(Site.YOUTUBE to agora - 5_000), agora))
        assertEquals(
            Site.INSTAGRAM,
            siteEmUso("com.android.chrome", navegadores, mapOf(Site.YOUTUBE to agora - 9_000, Site.INSTAGRAM to agora - 1_000), agora),
        )
        assertNull(siteEmUso("com.android.chrome", navegadores, mapOf(Site.YOUTUBE to agora - SITE_RECENTE_MS - 1), agora))
        // Outro app na frente, com o site pedido há pouco (o app do Instagram em segundo plano): não
        assertNull(siteEmUso("com.whatsapp", navegadores, mapOf(Site.INSTAGRAM to agora), agora))
        assertNull(siteEmUso(null, navegadores, mapOf(Site.INSTAGRAM to agora), agora))
    }

    @Test
    fun relogioDaTela() {
        assertEquals("7:00", mmss(LIVRE_MS))
        assertEquals("0:01", mmss(1))
        assertEquals("0:00", mmss(0))
        assertEquals("14:59", mmss(BLOQUEIO_MS - 1_000))
    }
}
