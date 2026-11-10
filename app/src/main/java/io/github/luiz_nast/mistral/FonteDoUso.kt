// O uso do dia aberto ao Chiron (mesmo celular): um provider só de leitura, protegido pela permissão USO, de assinatura
// (só app com a mesma chave lê). O Chiron pergunta quando precisa; o Mistral nunca o acorda. Duas rotas:
//   /dias    uma linha por dia e app dos últimos 14 dias: dia (AAAA-MM-DD), app (instagram | youtube), ms, atualizado_em
//   /celular uma linha: ultimo_uso (ms do último tique com a tela ligada, 0 = nunca), app (pacote na frente, ou ""),
//            escudo (1 = o escudo está de pé neste processo, 0 = não)
package io.github.luiz_nast.mistral

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.LocalDate

class FonteDoUso : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        Estado.carregar(context ?: return null)
        return when (uri.path) {
            "/dias" -> MatrixCursor(arrayOf("dia", "app", "ms", "atualizado_em")).also { c ->
                podarUso(Estado.uso, LocalDate.now()).forEach { c.addRow(arrayOf<Any?>(it.dia, it.app, it.ms, it.atualizadoEm)) }
            }
            "/celular" -> MatrixCursor(arrayOf("ultimo_uso", "app", "escudo")).also { c ->
                c.addRow(arrayOf<Any?>(Estado.ultimoUso, Estado.appNaFrente, if (Escudo.rodando) 1 else 0))
            }
            else -> null
        }
    }

    // Só leitura: escrever não faz nada
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
