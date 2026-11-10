// O estado do Mistral, guardado no celular: ligado ou não, e a rodada — uma só, do Instagram e do YouTube juntos
// (o dono, 10-31: pular de um site bloqueado pro outro furava o cadeado; agora os dois contam no mesmo tempo e caem
// juntos). A tela lê daqui ([rodada], [ligado]); quem escreve é o escudo ([Escudo]). Também guarda o uso do dia por app
// ([uso]) e o último tique com a tela ligada ([ultimoUso], [appNaFrente]), que o Chiron lê pela [FonteDoUso].
package io.github.luiz_nast.mistral

import android.content.Context
import android.content.SharedPreferences
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Estado {
    private lateinit var prefs: SharedPreferences

    private val _ligado = MutableStateFlow(false)

    /** O escudo está de pé: conta o uso e ejeta. */
    val ligado: StateFlow<Boolean> = _ligado.asStateFlow()

    private val _rodada = MutableStateFlow(Rodada())

    /** A rodada única: o Instagram e o YouTube somam no mesmo tempo e são bloqueados juntos. */
    val rodada: StateFlow<Rodada> = _rodada.asStateFlow()

    private val _manha = MutableStateFlow<Etapa?>(null)

    /** A etapa da manhã do Tourbillon que vale agora (null: as regras de sempre). Só na memória: o escudo pergunta de novo. */
    val manha: StateFlow<Etapa?> = _manha.asStateFlow()

    fun mudarManha(etapa: Etapa?) {
        _manha.value = etapa
    }

    /** O uso de tela de cada site por dia (14 dias), em memória; vai ao disco junto com a rodada ([atualizar]). */
    @Volatile
    var uso: List<UsoDoDia> = emptyList()
        private set

    /** O `agora` do último tique do escudo com a tela ligada (0: nunca). */
    @Volatile
    var ultimoUso = 0L
        private set

    /** O pacote que estava na frente nesse tique ("" se nenhum). */
    @Volatile
    var appNaFrente = ""
        private set

    /** Soma [passou] ms de [site] visível no dia de [agora] (fuso do celular). Só na memória. */
    fun somarUso(site: Site, passou: Long, agora: Long) {
        uso = somarUso(uso, site, passou, agora, ZoneId.systemDefault())
    }

    /** Anota que a tela estava ligada em [agora], com [pacote] na frente. Só na memória. */
    fun anotarTela(agora: Long, pacote: String?) {
        ultimoUso = agora
        appNaFrente = pacote.orEmpty()
    }

    /** Lê o que está guardado (uma vez por processo: a tela, o escudo e o reinício chamam). */
    @Synchronized
    fun carregar(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("estado", Context.MODE_PRIVATE)
        _ligado.value = prefs.getBoolean("ligado", false)
        _rodada.value = Rodada(prefs.getLong("usado", 0), prefs.getLong("ate", 0))
        uso = usoDoTexto(prefs.getString("uso", null))
        ultimoUso = prefs.getLong("ultimoUso", 0)
        appNaFrente = prefs.getString("appNaFrente", "").orEmpty()
    }

    fun ligar(ligado: Boolean) {
        _ligado.value = ligado
        prefs.edit().putBoolean("ligado", ligado).apply()
    }

    /** Está bloqueado agora (a tela não deixa desligar no meio de um bloqueio). */
    fun bloqueado(agora: Long): Boolean = _rodada.value.bloqueada(agora)

    /** A rodada nova; grava no disco quando [gravar], com o uso e a última tela (o escudo grava a cada poucos segundos e quando um bloqueio começa ou acaba). */
    fun atualizar(nova: Rodada, gravar: Boolean) {
        _rodada.value = nova
        if (!gravar) return
        prefs.edit().putLong("usado", nova.usadoMs).putLong("ate", nova.bloqueadoAte)
            .putString("uso", usoParaTexto(uso)).putLong("ultimoUso", ultimoUso).putString("appNaFrente", appNaFrente).apply()
    }
}
