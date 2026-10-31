// Qual app está na frente, pelo histórico de uso do Android (a permissão "Acesso ao uso", dada nas Configurações), e
// quais apps são navegadores. Sem serviço de acessibilidade: os bancos (o Nubank) travam quem tem um fora da Play Store.
package io.github.luiz_nast.maverick.truck

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.net.toUri

class Uso(private val ctx: Context) {
    private val historico = ctx.getSystemService(UsageStatsManager::class.java)
    private var lidoAte = System.currentTimeMillis() - 10 * 60_000L // na primeira vez, os últimos minutos dizem quem está na frente
    private var naFrente: String? = null

    /** O pacote do app na frente agora (o último que abriu uma tela), lendo só o que aconteceu desde a última vez. */
    fun naFrente(): String? {
        val agora = System.currentTimeMillis()
        // Com 3 s de sobra pra trás: o Android grava o evento um pouco depois da hora dele, e sem a sobra um "abriu o
        // Instagram" podia cair entre duas leituras e a sessão inteira não contar (revisão de 10-31). Reler é inofensivo
        val eventos = runCatching { historico.queryEvents(lidoAte - SOBRA_MS, agora) }.getOrNull() ?: return naFrente
        val e = UsageEvents.Event()
        while (eventos.hasNextEvent()) {
            eventos.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> naFrente = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == naFrente) naFrente = null
            }
        }
        lidoAte = agora
        return naFrente
    }

    companion object {
        private const val SOBRA_MS = 3_000L

        /** O app tem o "Acesso ao uso" (sem ele, o escudo não sabe quando o Instagram ou o YouTube está aberto). */
        fun liberado(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }

        /** Os apps que abrem um link qualquer da internet: os navegadores. */
        fun navegadores(ctx: Context): Set<String> {
            val link = Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
            return ctx.packageManager.queryIntentActivities(link, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }.toSet() - ctx.packageName
        }

        /** O número de usuário (uid) de cada pacote instalado de [pacotes]. */
        fun uids(ctx: Context, pacotes: Set<String>): Set<Int> =
            pacotes.mapNotNull { runCatching { ctx.packageManager.getPackageUid(it, 0) }.getOrNull() }.toSet()
    }
}
