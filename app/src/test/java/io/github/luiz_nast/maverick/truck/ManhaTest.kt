package io.github.luiz_nast.maverick.truck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A bateria Tourbillon ↔ Maverick Truck (o dono, 11-08): o que a etapa da manhã do Tourbillon muda em cada site, e a
// falha fechada (sem resposta, vencido, absurdo ou relógio mexido: as regras de sempre).
class ManhaTest {
    private val agora = 50_000_000L
    private val livre = Rodada(usadoMs = 3 * 60_000L)
    private val bloqueada = Rodada(LIVRE_MS, agora + 10 * 60_000L)

    /** Quem leva a tela de bloqueio, com [site] na frente: o próprio site, ou null (livre). */
    private fun ejeta(site: Site, etapa: Etapa?, rodada: Rodada) = decidir(rodada, site, etapa, agora, 1_000).second

    @Test
    fun etapaVezesSite() {
        for (r in listOf(livre, bloqueada)) {
            // Caminhada: Instagram livre, YouTube trancado; câmera: o contrário; conversa: os dois trancados
            assertNull(ejeta(Site.INSTAGRAM, Etapa.CAMINHADA, r))
            assertEquals(Site.YOUTUBE, ejeta(Site.YOUTUBE, Etapa.CAMINHADA, r))
            assertEquals(Site.INSTAGRAM, ejeta(Site.INSTAGRAM, Etapa.CAMERA, r))
            assertNull(ejeta(Site.YOUTUBE, Etapa.CAMERA, r))
            assertEquals(Site.INSTAGRAM, ejeta(Site.INSTAGRAM, Etapa.CONVERSA, r))
            assertEquals(Site.YOUTUBE, ejeta(Site.YOUTUBE, Etapa.CONVERSA, r))
        }
        // Fora da manhã, as regras de sempre: livre na rodada livre, bloqueado na rodada bloqueada
        for (site in Site.entries) {
            assertNull(ejeta(site, null, livre))
            assertEquals(site, ejeta(site, null, bloqueada))
        }
    }

    @Test
    fun oTempoDaManhaNaoContaNosSeteMinutos() {
        var r = livre
        var t = agora
        repeat(600) { // 10 min de Instagram na caminhada
            t += 1_000
            r = decidir(r, Site.INSTAGRAM, Etapa.CAMINHADA, t, 1_000).first
        }
        assertEquals(livre, r)
        // Acabou a manhã: o ciclo segue de onde estava
        assertEquals(livre.usadoMs + 1_000, decidir(r, Site.INSTAGRAM, null, t + 1_000, 1_000).first.usadoMs)
    }

    @Test
    fun oValeAteVenceEOAbsurdoNaoVale() {
        assertEquals(Etapa.CAMINHADA, etapaQueVale("CAMINHADA", agora + 90_000, agora))
        assertEquals(Etapa.CAMERA, etapaQueVale("CAMERA", agora + 1, agora))
        assertEquals(Etapa.CONVERSA, etapaQueVale("CONVERSA", agora + VALE_ATE_MAX_MS, agora))
        // Vencido, longe demais no futuro, "nenhuma", desconhecida ou sem resposta: as regras de sempre
        assertNull(etapaQueVale("CAMINHADA", agora, agora))
        assertNull(etapaQueVale("CAMINHADA", agora - 1, agora))
        assertNull(etapaQueVale("CAMINHADA", agora + VALE_ATE_MAX_MS + 1, agora))
        assertNull(etapaQueVale("nenhuma", agora + 90_000, agora))
        assertNull(etapaQueVale("caminhada", agora + 90_000, agora))
        assertNull(etapaQueVale("PASSEIO", agora + 90_000, agora))
        assertNull(etapaQueVale(null, agora + 90_000, agora))
        assertNull(etapaQueVale("CAMINHADA", 0, agora))
    }

    @Test
    fun oTourbillonMorreNoMeioDaCaminhada() {
        // A última resposta antes de morrer: caminhada, valendo 90 s. Ninguém responde mais
        val valeAte = agora + 90_000
        val etapa = { t: Long -> etapaQueVale("CAMINHADA", valeAte, t) }
        assertNull(decidir(bloqueada, Site.INSTAGRAM, etapa(agora + 89_999), agora + 89_999, 1_000).second)
        // Em até 90 s o bloqueio volta
        assertEquals(Site.INSTAGRAM, decidir(bloqueada, Site.INSTAGRAM, etapa(agora + 90_000), agora + 90_000, 1_000).second)
    }

    @Test
    fun reiniciarOCelularNoMeioDaManha() {
        // A manhã não é gravada: depois de ligar de novo, sem resposta do Tourbillon, valem as regras de sempre,
        // com a rodada gravada (o uso de antes da manhã, que a manhã não mexeu)
        var r = livre
        repeat(60) { r = decidir(r, Site.INSTAGRAM, Etapa.CAMINHADA, agora + it * 1_000L, 1_000).first }
        val depois = agora + 120_000
        assertEquals(Site.YOUTUBE, decidir(bloqueada.copy(bloqueadoAte = depois + 60_000), Site.YOUTUBE, null, depois, 0).second)
        assertEquals(livre.usadoMs + 1_000, decidir(r, Site.INSTAGRAM, null, depois, 1_000).first.usadoMs)
    }

    @Test
    fun relogioAdiantadoOuAtrasadoNaoSoltaNada() {
        val valeAte = agora + 90_000
        // Adiantado 2 min: venceu, as regras de sempre (até o Tourbillon renovar com o relógio novo)
        assertNull(etapaQueVale("CAMINHADA", valeAte, agora + 120_000))
        // Atrasado 1 h: o "vale até" fica a 1 h no futuro, absurdo: as regras de sempre
        assertNull(etapaQueVale("CAMINHADA", valeAte, agora - 3_600_000))
        // Renovado pelo Tourbillon depois da mudança: volta a valer
        assertEquals(Etapa.CAMINHADA, etapaQueVale("CAMINHADA", agora - 3_600_000 + 90_000, agora - 3_600_000))
    }

    @Test
    fun oYoutubeDentroDoTourbillonNaoConta() {
        // A página do YouTube da câmera é um WebView do Tourbillon: ele não é navegador nem app regrado
        val nav = setOf("com.android.chrome")
        val yt = mapOf(Site.YOUTUBE to Consulta(agora - 1_000, -1))
        assertNull(siteEmUso("com.implacavel.alarme", 10_300, nav, yt, agora))
    }
}
