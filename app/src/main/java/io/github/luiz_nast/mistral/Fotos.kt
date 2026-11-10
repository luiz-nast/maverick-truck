// As fotos da galeria do dono no carrossel da tela de bloqueio, junto com os carros (o dono, 10-31: "o dia que eu tiver
// uma foto da galeria eu pego de lá"). Escolhidas pelo seletor de fotos do Android (não pede permissão de galeria),
// COPIADAS pra dentro do app em tamanho de tela (no máximo 1600 px no lado maior), então apagar da galeria não tira
// daqui. Apaga na tela do app.
package io.github.luiz_nast.mistral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object Fotos {
    private const val LADO_MAXIMO = 1600
    private lateinit var pasta: File
    private val _lista = MutableStateFlow<List<File>>(emptyList())

    /** As fotos da galeria guardadas no app, da mais antiga pra mais nova. */
    val lista: StateFlow<List<File>> = _lista.asStateFlow()

    /** Uma vez por processo (a tela e a tela de bloqueio chamam). */
    @Synchronized
    fun carregar(ctx: Context) {
        if (::pasta.isInitialized) return
        pasta = File(ctx.applicationContext.filesDir, "fotos").apply { mkdirs() }
        reler()
    }

    @Synchronized
    private fun reler() {
        _lista.value = (pasta.listFiles() ?: emptyArray()).filter { it.isFile && it.length() > 0 }.sortedBy { it.name }
    }

    /** Copia a foto em [uri] (do seletor), já virada certa e reduzida. Fora da linha principal. */
    fun adicionar(ctx: Context, uri: Uri): Boolean {
        carregar(ctx)
        val destino = File(pasta, "foto_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
        val ok = runCatching {
            val fonte = ImageDecoder.createSource(ctx.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(fonte) { decodificador, info, _ ->
                val maior = maxOf(info.size.width, info.size.height)
                if (maior > LADO_MAXIMO) {
                    val escala = LADO_MAXIMO.toFloat() / maior
                    decodificador.setTargetSize((info.size.width * escala).toInt(), (info.size.height * escala).toInt())
                }
                decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            destino.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            destino.length() > 0
        }.onFailure { Log.w(TAG, "Fotos: não deu pra copiar $uri", it) }.getOrDefault(false)
        if (!ok) destino.delete()
        reler()
        return ok
    }

    /** Tira a foto do app (a da galeria fica onde está). */
    fun apagar(foto: File) {
        if (foto.parentFile == pasta) foto.delete()
        reler()
    }
}
