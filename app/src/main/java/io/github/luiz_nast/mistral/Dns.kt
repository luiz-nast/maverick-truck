// O que o escudo faz com os pacotes do túnel, puro (sem Android), com testes em DnsTest: ler a pergunta de DNS de um
// pacote IPv4/UDP, responder "não existe" (NXDOMAIN), ler os endereços de uma resposta e montar o pacote de volta.
package io.github.luiz_nast.mistral


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

/**
 * [dns] com a validade (TTL) de cada registro limitada a [maxSegundos], pros sites regrados: o celular e o navegador
 * perguntam de novo logo (o uso no navegador aparece, e o bloqueio pega em segundos). O registro OPT (tipo 41) não tem
 * TTL de verdade (os campos dele são outros) e fica como está. Uma resposta que não dá pra ler volta igual.
 */
fun encurtarTtl(dns: ByteArray, maxSegundos: Int): ByteArray {
    if (dns.size < 12) return dns
    val r = dns.copyOf()
    var i = 12
    repeat(u16(r, 4)) {
        i = pularNome(r, i) ?: return dns
        i += 4
    }
    val registros = u16(r, 6) + u16(r, 8) + u16(r, 10)
    repeat(registros) {
        i = pularNome(r, i) ?: return r
        if (i + 10 > r.size) return r
        if (u16(r, i) != 41) {
            val ttl = (u16(r, i + 4).toLong() shl 16) or u16(r, i + 6).toLong()
            if (ttl > maxSegundos) {
                escrever16(r, i + 4, 0)
                escrever16(r, i + 6, maxSegundos)
            }
        }
        i += 10 + u16(r, i + 8)
        if (i > r.size) return r
    }
    return r
}

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
