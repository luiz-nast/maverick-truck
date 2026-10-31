package io.github.luiz_nast.maverick.truck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DnsTest {

    /** Uma pergunta de DNS do tipo [tipo] (1 = A) pra [nome], com a identificação 0x1234 e "recursão desejada". */
    private fun pergunta(nome: String, tipo: Int = 1): ByteArray {
        val corpo = mutableListOf<Byte>()
        for (parte in nome.split('.')) {
            corpo += parte.length.toByte()
            parte.forEach { corpo += it.code.toByte() }
        }
        corpo.add(0)
        corpo += listOf(0, tipo, 0, 1).map { it.toByte() }
        return byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0) + corpo.toByteArray()
    }

    private val celular = byteArrayOf(10, 215.toByte(), 173.toByte(), 1)
    private val dns = byteArrayOf(10, 215.toByte(), 173.toByte(), 2)

    @Test
    fun lePerguntaDeUmPacoteDoTunel() {
        val p = pacoteUdp(celular, dns, 40_000, 53, pergunta("www.Instagram.com"))
        val lida = lerPerguntaDns(p, p.size)
        assertNotNull(lida)
        lida!!
        assertArrayEquals(celular, lida.origem)
        assertArrayEquals(dns, lida.destino)
        assertEquals(40_000, lida.portaOrigem)
        assertEquals("www.instagram.com", nomeDaPergunta(lida.dns)?.first)
        // O pacote que montamos tem as duas somas de verificação certas
        assertEquals(0, somaDe(p, 0, 20))
    }

    @Test
    fun ignoraOQueNaoEPerguntaDeDns() {
        val p = pacoteUdp(celular, dns, 40_000, 443, pergunta("www.instagram.com")) // não é a porta 53
        assertNull(lerPerguntaDns(p, p.size))
        val tcp = pacoteUdp(celular, dns, 40_000, 53, pergunta("x.com")).also { it[9] = 6 }
        assertNull(lerPerguntaDns(tcp, tcp.size))
        val v6 = ByteArray(60).also { it[0] = 0x60 }
        assertNull(lerPerguntaDns(v6, v6.size))
        assertNull(lerPerguntaDns(ByteArray(10), 10))
    }

    @Test
    fun respondeNaoExiste() {
        val q = pergunta("youtube.com")
        val r = respostaNaoExiste(q)!!
        assertEquals(0x12, r[0].toInt())
        assertEquals(0x34, r[1].toInt())
        assertEquals(0x81, r[2].toInt() and 0xFF) // resposta, com a "recursão desejada" da pergunta
        assertEquals(3, r[3].toInt() and 0x0F) // NXDOMAIN
        assertEquals(1, r[5].toInt()) // a pergunta vai de volta
        assertEquals(0, r[7].toInt()) // e nenhuma resposta
        assertEquals("youtube.com", nomeDaPergunta(r)?.first)
        assertEquals(q.size, r.size)
    }

    @Test
    fun leOsEnderecosDeUmaResposta() {
        val q = pergunta("i.instagram.com")
        // Resposta: um CNAME (com o nome comprimido apontando pra pergunta) e um A
        val cabecalho = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0)
        val pergunta = q.copyOfRange(12, q.size)
        val cname = byteArrayOf(0xC0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0.toByte(), 12)
        val a = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 157.toByte(), 240.toByte(), 12, 63)
        val resposta = cabecalho + pergunta + cname + a
        val enderecos = enderecosDaResposta(resposta)
        assertEquals(1, enderecos.size)
        assertEquals("157.240.12.63", textoDoEndereco(enderecos[0]))
        // Um erro (NXDOMAIN) não tem endereço
        assertEquals(0, enderecosDaResposta(respostaNaoExiste(q)!!).size)
        // Truncada no meio: o que deu pra ler
        assertEquals(0, enderecosDaResposta(resposta.copyOf(resposta.size - 3)).size)
    }

    @Test
    fun encurtaAValidadeDasRespostas() {
        val q = pergunta("rr3---sn-x.googlevideo.com")
        val cabecalho = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 1)
        // Um A com 300 s e um OPT (tipo 41, cujo "TTL" são outras coisas e fica igual)
        val a = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 1, 44, 0, 4, 142.toByte(), 250.toByte(), 1, 1)
        val opt = byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0x80.toByte(), 0, 0, 0)
        val resposta = cabecalho + q.copyOfRange(12, q.size) + a + opt
        val curta = encurtarTtl(resposta, 10)
        val ttlDoA = 12 + (q.size - 12) + 6
        assertEquals(listOf(0, 0, 0, 10), (ttlDoA until ttlDoA + 4).map { curta[it].toInt() and 0xFF })
        val optInicio = resposta.size - opt.size
        assertArrayEquals(opt, curta.copyOfRange(optInicio, curta.size))
        // O resto, igual; e o endereço continua lá
        assertEquals("142.250.1.1", textoDoEndereco(enderecosDaResposta(curta)[0]))
        // Validade menor que o máximo fica como está
        assertArrayEquals(curta, encurtarTtl(curta, 10))
    }

    @Test
    fun soEnderecosDaInternetDeVerdade() {
        fun v4(vararg b: Int) = b.map { it.toByte() }.toByteArray()
        assertTrue(enderecoPublico(v4(157, 240, 12, 63)))
        assertTrue(enderecoPublico(v4(142, 250, 79, 46)))
        assertFalse(enderecoPublico(v4(192, 168, 0, 1)))
        assertFalse(enderecoPublico(v4(10, 0, 0, 1)))
        assertFalse(enderecoPublico(v4(172, 20, 1, 1)))
        assertFalse(enderecoPublico(v4(100, 64, 0, 1)))
        assertFalse(enderecoPublico(v4(127, 0, 0, 1)))
        assertFalse(enderecoPublico(v4(0, 0, 0, 0)))
        assertFalse(enderecoPublico(v4(224, 0, 0, 1)))
        assertTrue(enderecoPublico(java.net.InetAddress.getByName("2a03:2880:f12f:83:face:b00c:0:25de").address))
        assertFalse(enderecoPublico(java.net.InetAddress.getByName("fe80::1").address))
        assertFalse(enderecoPublico(java.net.InetAddress.getByName("fd00::1").address))
        assertFalse(enderecoPublico(java.net.InetAddress.getByName("::1").address))
    }

    /** Soma de verificação de [n] bytes a partir de [i]: 0 quando o campo de soma dentro deles está certo. */
    private fun somaDe(b: ByteArray, i: Int, n: Int): Int {
        var s = 0L
        var k = i
        while (k < i + n) {
            s += ((b[k].toInt() and 0xFF) shl 8) or (b[k + 1].toInt() and 0xFF)
            k += 2
        }
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }
}
