// Teste do bloqueio sem esperar os 7 minutos, só pelo cabo (permissão DUMP, que o adb tem e app nenhum):
//   adb shell am broadcast -n io.github.luiz_nast.mistral/.TesteDoBloqueio --ei minutos 2
//   adb logcat -s Mistral
// "minutos 0" solta na hora. O escudo precisa estar ligado. Bloqueia o Instagram e o YouTube juntos (uma rodada só).
package io.github.luiz_nast.mistral

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class TesteDoBloqueio : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Estado.carregar(ctx)
        val minutos = intent.getIntExtra("minutos", 2).coerceIn(0, (BLOQUEIO_MS / 60_000).toInt())
        val agora = System.currentTimeMillis()
        Estado.atualizar(if (minutos > 0) Rodada(LIVRE_MS, agora + minutos * 60_000L) else Rodada(), gravar = true)
        Log.i(TAG, "Teste: Instagram e YouTube ${if (minutos > 0) "bloqueados por $minutos min" else "livres"}")
        // Um tique já, mesmo com a tela apagada: a tela de bloqueio aparece agora
        if (Estado.ligado.value) Escudo.ligar(ctx)
    }
}
