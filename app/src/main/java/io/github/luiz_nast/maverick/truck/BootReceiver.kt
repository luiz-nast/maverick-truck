package io.github.luiz_nast.maverick.truck

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * O celular ligou de novo, ou o app foi atualizado: o escudo, se estava ligado, volta sozinho. Sem a permissão da VPN
 * (outra VPN tomou o lugar), a notificação pede pra religar pelo app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Estado.carregar(ctx)
        if (!Estado.ligado.value) return
        if (VpnService.prepare(ctx) != null) {
            Notificacoes.criarCanais(ctx)
            Notificacoes.avisarReligar(ctx)
            return
        }
        Escudo.ligar(ctx)
    }
}
