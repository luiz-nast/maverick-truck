// As músicas da tela de bloqueio: a MESMA biblioteca de sons do Tourbillon Alarm (o despertador do dono), lida e escrita
// pela FonteDeSons dele (o dono, 10-31: a mesma base de músicas nos dois). Daqui dá pra tocar, adicionar e apagar; o que
// entra ou sai aparece nos dois apps. Só funciona com o Tourbillon instalado e assinado com a mesma chave (a permissão
// com.implacavel.alarme.permission.SONS é de assinatura); sem ele, a lista vem vazia e a tela fica sem som.
package io.github.luiz_nast.maverick.truck

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlin.math.roundToInt
import kotlin.random.Random

object Musicas {
    private const val AUTORIDADE = "com.implacavel.alarme.sons"
    private val base: Uri = Uri.parse("content://$AUTORIDADE")

    /** Os nomes da biblioteca; null se o Tourbillon não está instalado (ou não deixou). Chama o outro app: fora da linha principal. */
    fun listar(ctx: Context): List<String>? = runCatching {
        ctx.contentResolver.query(base, null, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }.onFailure { Log.w(TAG, "Músicas: sem a biblioteca do Tourbillon", it) }.getOrNull()

    /** O arquivo do som [nome], pra tocar (quem abre fecha); null se sumiu. */
    fun abrir(ctx: Context, nome: String): ParcelFileDescriptor? = runCatching {
        ctx.contentResolver.openFileDescriptor(som(nome), "r")
    }.onFailure { Log.w(TAG, "Músicas: não abriu $nome", it) }.getOrNull()

    /** Manda o áudio em [origem] (escolhido no celular) pra biblioteca, com o nome do arquivo. Fora da linha principal. */
    fun adicionar(ctx: Context, origem: Uri): Boolean = runCatching {
        val nome = ctx.contentResolver.query(origem, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Sound"
        val destino = base.buildUpon().appendPath("novo").appendPath(nome).build()
        ctx.contentResolver.openInputStream(origem)!!.use { entrada ->
            ctx.contentResolver.openOutputStream(destino, "w")!!.use { saida -> entrada.copyTo(saida) }
        }
        Log.i(TAG, "Músicas: mandado pra biblioteca: $nome")
        true
    }.onFailure { Log.w(TAG, "Músicas: não deu pra adicionar $origem", it) }.getOrDefault(false)

    /** Apaga o som [nome] da biblioteca (some dos dois apps). Fora da linha principal. */
    fun apagar(ctx: Context, nome: String): Boolean = runCatching {
        ctx.contentResolver.delete(som(nome), null, null) > 0
    }.onFailure { Log.w(TAG, "Músicas: não apagou $nome", it) }.getOrDefault(false)

    private fun som(nome: String): Uri = base.buildUpon().appendPath("som").appendPath(nome).build()
}

/** O volume em que a música começa: 35% do máximo do volume de mídia (o dono, 10-31). Depois, a pessoa mexe como quiser. */
const val VOLUME_DA_MUSICA = 0.35f

fun volumeDaMusica(maximo: Int): Int = (maximo * VOLUME_DA_MUSICA).roundToInt().coerceIn(1, maximo.coerceAtLeast(1))

/** O próximo som, aleatório, sem repetir o [anterior] quando há outro; null com a biblioteca vazia. */
fun sortearSom(nomes: List<String>, anterior: String?, sorteio: Random = Random): String? {
    if (nomes.isEmpty()) return null
    val outros = nomes.filter { it != anterior }.ifEmpty { nomes }
    return outros[sorteio.nextInt(outros.size)]
}
