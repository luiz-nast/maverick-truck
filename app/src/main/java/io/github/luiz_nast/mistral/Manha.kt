// A atividade da manhã do Tourbillon Alarm manda no Mistral enquanto dura (o dono, 11-08). O Tourbillon diz a
// etapa pelo provider content://com.implacavel.alarme.modo, com a mesma permissão de assinatura da biblioteca de sons
// (Musicas): uma linha com "etapa" (CONVERSA, CAMINHADA, CAMERA ou nenhuma) e "valeAte" (epoch, renovado enquanto
// dura). O que cada etapa muda fica em Regras.kt (etapaQueVale, decidir); o escudo pergunta a cada poucos segundos.
package io.github.luiz_nast.mistral

import android.content.Context
import android.net.Uri

object Manha {
    private val endereco: Uri = Uri.parse("content://com.implacavel.alarme.modo")

    /**
     * A etapa e até quando ela vale; null sem o Tourbillon (ou sem o provider, ou sem a permissão) e com a resposta
     * vazia. Chama o outro app: fora da linha principal.
     */
    fun ler(ctx: Context): Pair<String?, Long>? = runCatching {
        ctx.contentResolver.query(endereco, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            c.getString(c.getColumnIndexOrThrow("etapa")) to c.getLong(c.getColumnIndexOrThrow("valeAte"))
        }
    }.getOrNull()
}
