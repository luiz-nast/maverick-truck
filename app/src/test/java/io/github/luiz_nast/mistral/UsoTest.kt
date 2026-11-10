package io.github.luiz_nast.mistral

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// A conta do uso do dia por app (o que o Chiron lê): só o site visível conta, no dia do fuso, com o teto do tique,
// 14 dias guardados e o texto de ida e volta. O exemplo fixo é o da 2.2 do PLANO_MISTRAL_ICONES.
class UsoTest {
    private val fuso = ZoneId.of("America/Sao_Paulo")

    private fun epoch(ano: Int, mes: Int, dia: Int, h: Int, m: Int = 0, s: Int = 0, ms: Int = 0) =
        ZonedDateTime.of(ano, mes, dia, h, m, s, ms * 1_000_000, fuso).toInstant().toEpochMilli()

    @Test
    fun tresTiquesDeUmSegundoSomamTresMil() {
        val t = epoch(2026, 11, 10, 10)
        var lista = emptyList<UsoDoDia>()
        repeat(3) { i -> lista = somarUso(lista, Site.INSTAGRAM, 1_000, t + i * 1_000L, fuso) }
        assertEquals(listOf(UsoDoDia("2026-11-10", "instagram", 3_000, t + 2_000)), lista)
    }

    @Test
    fun umTiqueDeCincoSegundosSomaDoisMil() {
        val t = epoch(2026, 11, 10, 10)
        assertEquals(2_000L, somarUso(emptyList(), Site.YOUTUBE, 5_000, t, fuso).single().ms)
        // Relógio que voltou: passou negativo não tira nada
        assertEquals(0L, somarUso(emptyList(), Site.YOUTUBE, -3_000, t, fuso).single().ms)
    }

    @Test
    fun siteEjetadoNaoEVisivel() {
        assertNull(siteVisivel(Site.INSTAGRAM, Site.INSTAGRAM))
        assertNull(siteVisivel(null, null))
        assertNull(siteVisivel(null, Site.YOUTUBE))
        assertEquals(Site.INSTAGRAM, siteVisivel(Site.INSTAGRAM, null))
        assertEquals(Site.INSTAGRAM, siteVisivel(Site.INSTAGRAM, Site.YOUTUBE))
    }

    @Test
    fun naManhaOLivreContaEOTrancadoNao() {
        val agora = 50_000_000L
        // Caminhada: Instagram livre (conta), YouTube ejetado (não conta); o uso da manhã não entra na rodada
        for ((site, conta) in listOf(Site.INSTAGRAM to true, Site.YOUTUBE to false)) {
            val ejetar = decidir(Rodada(), site, Etapa.CAMINHADA, agora, 1_000).second
            assertEquals(if (conta) site else null, siteVisivel(site, ejetar))
        }
    }

    @Test
    fun oTiqueDaMeiaNoiteCaiNoDiaNovo() {
        val ontem = somarUso(emptyList(), Site.INSTAGRAM, 1_000, epoch(2026, 11, 10, 23, 59, 59, 900), fuso)
        val lista = somarUso(ontem, Site.INSTAGRAM, 1_000, epoch(2026, 11, 11, 0, 0, 0, 500), fuso)
        assertEquals(listOf("2026-11-10" to 1_000L, "2026-11-11" to 1_000L), lista.map { it.dia to it.ms })
    }

    @Test
    fun podaGuardaQuatorzeDias() {
        val hoje = LocalDate.of(2026, 11, 10)
        val lista = listOf(
            UsoDoDia("2026-10-27", "instagram", 1, 0), // 14 dias atrás: sai
            UsoDoDia("2026-10-28", "youtube", 2, 0), // 13 dias atrás: fica
            UsoDoDia("2026-10-28", "instagram", 3, 0),
            UsoDoDia("2026-11-10", "instagram", 4, 0),
        )
        assertEquals(
            listOf("2026-10-28 instagram", "2026-10-28 youtube", "2026-11-10 instagram"),
            podarUso(lista, hoje).map { "${it.dia} ${it.app}" },
        )
        // Somar também poda
        val t = epoch(2026, 11, 10, 10)
        assertEquals(4, somarUso(lista, Site.YOUTUBE, 1_000, t, fuso).size)
    }

    @Test
    fun textoIdaEVoltaComOExemploFixo() {
        val agora = 1794326400000L // 10/11/2026 13:00 em Brasília
        assertEquals(epoch(2026, 11, 10, 13), agora)
        val lista = listOf(UsoDoDia("2026-11-10", "instagram", 754_000, agora), UsoDoDia("2026-11-10", "youtube", 120_000, agora))
        val texto = usoParaTexto(lista)
        assertEquals("2026-11-10 instagram 754000 1794326400000\n2026-11-10 youtube 120000 1794326400000", texto)
        assertEquals(lista, usoDoTexto(texto))
        assertEquals(emptyList<UsoDoDia>(), usoDoTexto(null))
        assertEquals(emptyList<UsoDoDia>(), usoDoTexto(""))
    }

    @Test
    fun linhaQuebradaFicaFora() {
        val texto = listOf(
            "2026-11-10 instagram 754000 1794326400000",
            "lixo",
            "2026-11-10 youtube abc 1794326400000", // ms que não é número
            "2026-11-10 youtube -5 1794326400000", // ms negativo
            "2026-13-45 youtube 1 1794326400000", // dia que não existe
            "2026-11-10 youtube 1", // campo a menos
            "2026-11-10 youtube 1 2 3", // campo a mais
            "2026-11-10 youtube 120000 1794326400000",
        ).joinToString("\n")
        assertEquals(listOf(754_000L, 120_000L), usoDoTexto(texto).map { it.ms })
    }
}
