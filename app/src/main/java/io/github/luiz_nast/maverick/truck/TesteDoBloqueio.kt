// Teste do bloqueio sem esperar os 7 minutos, só pelo cabo (permissão DUMP, que o adb tem e app nenhum):
//   adb shell am broadcast -n io.github.luiz_nast.maverick.truck/.TesteDoBloqueio --es site INSTAGRAM --ei minutos 2
//   adb logcat -s Maverick
// "minutos 0" solta o site na hora. O escudo precisa estar ligado.
package io.github.luiz_nast.maverick.truck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TesteDoBloqueio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Estado.carregar(ctx)
        val nome = intent.getStringExtra("site") ?: Site.INSTAGRAM.name
        val site = Site.entries.firstOrNull { it.name.equals(nome, ignoreCase = true) } ?: return
        val minutos = intent.getIntExtra("minutos", 2).coerceIn(0, (BLOQUEIO_MS / 60_000).toInt())
        val agora = System.currentTimeMillis()
        val novas = Estado.rodadas.value.toMutableMap()
        novas[site] = if (minutos > 0) Rodada(LIVRE_MS, agora + minutos * 60_000L) else Rodada()
        Estado.atualizar(novas, gravar = true)
        Log.i(TAG, "Teste: ${site.nome} ${if (minutos > 0) "bloqueado por $minutos min" else "livre"}")
        // Um tique já, mesmo com a tela apagada: o túnel muda agora
        if (Estado.ligado.value) Escudo.ligar(ctx)
    }
}
