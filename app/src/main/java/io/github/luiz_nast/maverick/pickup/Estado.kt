// O estado do Maverick Pickup, guardado no celular: ligado ou não, a rodada de cada site e os endereços que o escudo
// corta no bloqueio. A tela lê daqui ([rodadas], [ligado]); quem escreve é o escudo ([Escudo]).
package io.github.luiz_nast.maverick.pickup

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object Estado {
    /** Endereços guardados por site, no máximo: os mais recentes ficam. */
    private const val ENDERECOS_MAX = 200

    private lateinit var prefs: SharedPreferences

    private val _ligado = MutableStateFlow(false)

    /** O escudo está de pé: conta o uso e bloqueia. */
    val ligado: StateFlow<Boolean> = _ligado.asStateFlow()

    private val _rodadas = MutableStateFlow(Site.entries.associateWith { Rodada() })

    /** A rodada de cada site agora. */
    val rodadas: StateFlow<Map<Site, Rodada>> = _rodadas.asStateFlow()

    private val enderecos = Site.entries.associateWith { LinkedHashSet<String>() }

    /** Lê o que está guardado (uma vez por processo: a tela, o escudo e o reinício chamam). */
    @Synchronized
    fun carregar(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("estado", Context.MODE_PRIVATE)
        _ligado.value = prefs.getBoolean("ligado", false)
        _rodadas.value = Site.entries.associateWith { Rodada(prefs.getLong("${it.name}_usado", 0), prefs.getLong("${it.name}_ate", 0)) }
        for (site in Site.entries) {
            prefs.getString("${site.name}_enderecos", "")?.split(',')?.filter { it.isNotBlank() }?.let { enderecos.getValue(site).addAll(it) }
        }
    }

    fun ligar(ligado: Boolean) {
        _ligado.value = ligado
        prefs.edit().putBoolean("ligado", ligado).apply()
    }

    /** Algum site está bloqueado agora (a tela não deixa desligar no meio de um bloqueio). */
    fun algumBloqueado(agora: Long): Boolean = _rodadas.value.values.any { it.bloqueada(agora) }

    fun bloqueado(site: Site, agora: Long): Boolean = _rodadas.value.getValue(site).bloqueada(agora)

    /** As rodadas novas; grava no disco quando [gravar] (o escudo grava a cada poucos segundos e quando um bloqueio começa ou acaba). */
    fun atualizar(novas: Map<Site, Rodada>, gravar: Boolean) {
        _rodadas.value = novas
        if (!gravar) return
        val e = prefs.edit()
        for ((site, r) in novas) e.putLong("${site.name}_usado", r.usadoMs).putLong("${site.name}_ate", r.bloqueadoAte)
        e.apply()
    }

    /** Os endereços que o DNS deu pro [site] (só os que servem a ele: [guardaEnderecos]), pra cortar no bloqueio. */
    @Synchronized
    fun enderecos(site: Site): List<String> = enderecos.getValue(site).toList()

    /** Guarda [novos] endereços do [site]; true se algum era novo. */
    @Synchronized
    fun guardarEnderecos(site: Site, novos: List<String>): Boolean {
        val conjunto = enderecos.getValue(site)
        var mudou = false
        for (endereco in novos) {
            if (endereco.isBlank()) continue
            // De novo vai pro fim: os mais recentes ficam quando passa do máximo
            if (conjunto.remove(endereco).not()) mudou = true
            conjunto.add(endereco)
        }
        while (conjunto.size > ENDERECOS_MAX) conjunto.remove(conjunto.first())
        if (mudou) prefs.edit().putString("${site.name}_enderecos", conjunto.joinToString(",")).apply()
        return mudou
    }
}
