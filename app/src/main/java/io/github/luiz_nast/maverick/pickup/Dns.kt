// O que o escudo faz com os pacotes do túnel, puro (sem Android), com testes em DnsTest: ler a pergunta de DNS de um
// pacote IPv4/UDP, responder "não existe" (NXDOMAIN), ler os endereços de uma resposta e montar o pacote de volta.
package io.github.luiz_nast.maverick.pickup

import java.net.InetAddress

/** Uma pergunta de DNS que chegou pelo túnel: de quem ([origem]:[portaOrigem]), pra quem ([destino]:[portaDestino]) e a mensagem. */
class PerguntaDns(val origem: ByteArray, val destino: ByteArray, val portaOrigem: Int, val portaDestino: Int, val dns: ByteArray)

private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

private fun escrever16(b: ByteArray, i: Int, valor: Int) {
    b[i] = (valor ushr 8).toByte()
    b[i + 1] = valor.toByte()
}

/**
 * A pergunta de DNS em [pacote] (os [tamanho] primeiros bytes): IPv4, UDP, pra porta 53, inteiro (sem fragmento) e com
 * pelo menos o cabeçalho do DNS. Null pra qualquer outra coisa (o escudo descarta: são as conexões cortadas no bloqueio).
 */
fun lerPerguntaDns(pacote: ByteArray, tamanho: Int): PerguntaDns? {
    if (tamanho < 28 || (pacote[0].toInt() ushr 4) and 0xF != 4) return null
    val cabecalho = (pacote[0].toInt() and 0xF) * 4
    if (cabecalho < 20 || tamanho < cabecalho + 8) return null
    if (pacote[9].toInt() and 0xFF != 17) return null // só UDP
    val maisPedacos = pacote[6].toInt() and 0x20 != 0
    val deslocamento = ((pacote[6].toInt() and 0x1F) shl 8) or (pacote[7].toInt() and 0xFF)
    if (maisPedacos || deslocamento != 0) return null
    val portaDestino = u16(pacote, cabecalho + 2)
    if (portaDestino != 53) return null
    val fim = minOf(u16(pacote, 2), tamanho, cabecalho + u16(pacote, cabecalho + 4))
    val inicio = cabecalho + 8
    if (fim - inicio < 12) return null
    return PerguntaDns(
        pacote.copyOfRange(12, 16), pacote.copyOfRange(16, 20), u16(pacote, cabecalho), portaDestino,
        pacote.copyOfRange(inicio, fim),
    )
}

/** O nome da primeira pergunta de [dns] (minúsculo) e onde ela acaba (depois do tipo e da classe). Null se não der pra ler. */
fun nomeDaPergunta(dns: ByteArray): Pair<String, Int>? {
    if (dns.size < 12 || u16(dns, 4) < 1) return null
    val nome = StringBuilder()
    var i = 12
    while (true) {
        if (i >= dns.size) return null
        val n = dns[i].toInt() and 0xFF
        if (n == 0) break
        if (n and 0xC0 != 0 || i + 1 + n > dns.size || nome.length + n > 253) return null // compressão numa pergunta não existe
        if (nome.isNotEmpty()) nome.append('.')
        for (k in 1..n) nome.append((dns[i + k].toInt() and 0xFF).toChar())
        i += 1 + n
    }
    i++ // o zero do fim do nome
    if (i + 4 > dns.size) return null
    return nome.toString().lowercase() to i + 4
}

/**
 * A resposta "esse nome não existe" (NXDOMAIN) pra [pergunta]: a mesma identificação, a pergunta repetida e nenhuma
 * resposta. O app (ou o navegador) desiste na hora, sem esperar. Null se a pergunta não dá pra ler.
 */
fun respostaNaoExiste(pergunta: ByteArray): ByteArray? {
    val (_, fim) = nomeDaPergunta(pergunta) ?: return null
    val r = pergunta.copyOfRange(0, fim)
    r[2] = (0x80 or (pergunta[2].toInt() and 0x79)).toByte() // resposta, o mesmo opcode, o mesmo "recursão desejada"
    r[3] = (0x80 or 3).toByte() // recursão disponível; código 3, NXDOMAIN
    escrever16(r, 4, 1)
    for (k in 6..11) r[k] = 0
    return r
}

/** Depois de um nome em [dns] a partir de [inicio] (com ou sem compressão); null se o nome passa do fim. */
private fun pularNome(dns: ByteArray, inicio: Int): Int? {
    var i = inicio
    while (i < dns.size) {
        val n = dns[i].toInt() and 0xFF
        when {
            n == 0 -> return i + 1
            n and 0xC0 == 0xC0 -> return if (i + 2 <= dns.size) i + 2 else null
            n and 0xC0 != 0 -> return null
            else -> i += 1 + n
        }
    }
    return null
}

/** Os endereços (IPv4 dos registros A, IPv6 dos AAAA) da seção de respostas de [dns]; vazio se é erro ou não dá pra ler. */
fun enderecosDaResposta(dns: ByteArray): List<ByteArray> {
    if (dns.size < 12 || dns[3].toInt() and 0x0F != 0) return emptyList()
    var i = 12
    repeat(u16(dns, 4)) {
        i = pularNome(dns, i) ?: return emptyList()
        i += 4
    }
    val enderecos = mutableListOf<ByteArray>()
    repeat(u16(dns, 6)) {
        i = pularNome(dns, i) ?: return enderecos
        if (i + 10 > dns.size) return enderecos
        val tipo = u16(dns, i)
        val tamanho = u16(dns, i + 8)
        i += 10
        if (i + tamanho > dns.size) return enderecos
        if ((tipo == 1 && tamanho == 4) || (tipo == 28 && tamanho == 16)) enderecos += dns.copyOfRange(i, i + tamanho)
        i += tamanho
    }
    return enderecos
}

/** "157.240.12.63", "2a03:2880:f12f:83:face:b00c:0:25de". */
fun textoDoEndereco(endereco: ByteArray): String = InetAddress.getByAddress(endereco).hostAddress ?: ""

/** A soma de verificação da internet (RFC 1071) de [tamanho] bytes de [b] a partir de [inicio], mais [somaInicial]. */
private fun soma(b: ByteArray, inicio: Int, tamanho: Int, somaInicial: Long = 0): Int {
    var s = somaInicial
    var i = inicio
    while (i + 1 < inicio + tamanho) {
        s += u16(b, i)
        i += 2
    }
    if (i < inicio + tamanho) s += (b[i].toInt() and 0xFF) shl 8
    while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
    return (s.inv() and 0xFFFF).toInt()
}

/** Um pacote IPv4/UDP de [origem]:[portaOrigem] pra [destino]:[portaDestino] com [dados], com as duas somas de verificação. */
fun pacoteUdp(origem: ByteArray, destino: ByteArray, portaOrigem: Int, portaDestino: Int, dados: ByteArray): ByteArray {
    val total = 28 + dados.size
    val p = ByteArray(total)
    p[0] = 0x45 // IPv4, cabeçalho de 20 bytes
    escrever16(p, 2, total)
    escrever16(p, 6, 0x4000) // não fragmentar
    p[8] = 64 // TTL
    p[9] = 17 // UDP
    System.arraycopy(origem, 0, p, 12, 4)
    System.arraycopy(destino, 0, p, 16, 4)
    escrever16(p, 10, soma(p, 0, 20))
    escrever16(p, 20, portaOrigem)
    escrever16(p, 22, portaDestino)
    escrever16(p, 24, 8 + dados.size)
    System.arraycopy(dados, 0, p, 28, dados.size)
    // A soma do UDP inclui o pseudo-cabeçalho: os dois endereços, o protocolo e o tamanho do UDP
    val pseudo = u16(p, 12).toLong() + u16(p, 14) + u16(p, 16) + u16(p, 18) + 17 + 8 + dados.size
    val somaUdp = soma(p, 20, 8 + dados.size, pseudo)
    escrever16(p, 26, if (somaUdp == 0) 0xFFFF else somaUdp)
    return p
}
