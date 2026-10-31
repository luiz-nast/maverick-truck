// As notificações: a fixa do escudo (a rodada de cada site, discreta) e o aviso de bloqueio (aparece por cima, uma
// vez quando o bloqueio começa e uma vez a cada abertura do app bloqueado).
package io.github.luiz_nast.maverick.truck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Notificacoes {
    const val ID_ESTADO = 1
    private const val ID_RELIGAR = 2
    private const val ID_BLOQUEIO = 10 // + o número do site

    private const val CANAL_ESTADO = "estado"
    private const val CANAL_BLOQUEIO = "bloqueio"

    fun criarCanais(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_ESTADO, "Shield status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Time used of each site, while the shield is on"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_BLOQUEIO, "Blocks", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When Instagram or YouTube gets blocked"
            },
        )
    }

    private fun abrirApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** A notificação fixa do escudo, com [texto]. */
    fun estado(ctx: Context, texto: String): Notification =
        NotificationCompat.Builder(ctx, CANAL_ESTADO)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("Maverick Truck")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(abrirApp(ctx))
            .build()

    fun mostrarEstado(ctx: Context, texto: String) = notificar(ctx, ID_ESTADO, estado(ctx, texto))

    /** O [site] está bloqueado até [ate] (epoch): o aviso por cima, que some sozinho quando o bloqueio acaba. */
    fun avisarBloqueio(ctx: Context, site: Site, ate: Long) {
        val hora = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ate).atZone(ZoneId.systemDefault()))
        val aviso = NotificationCompat.Builder(ctx, CANAL_BLOQUEIO)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("${site.nome} blocked")
            .setContentText("Your ${LIVRE_MS / 60_000} minutes are up. Free again at $hora.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter((ate - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setContentIntent(abrirApp(ctx))
            .build()
        notificar(ctx, ID_BLOQUEIO + site.ordinal, aviso)
    }

    /** O escudo caiu (a VPN foi tirada, ou a permissão dela sumiu): tocar abre o app pra religar. */
    fun avisarReligar(ctx: Context) {
        val aviso = NotificationCompat.Builder(ctx, CANAL_BLOQUEIO)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("Maverick Truck is off")
            .setContentText("Instagram and YouTube are not limited. Tap to turn it back on.")
            .setAutoCancel(true)
            .setContentIntent(abrirApp(ctx))
            .build()
        notificar(ctx, ID_RELIGAR, aviso)
    }

    /** Sem a permissão de notificações, nada (o escudo funciona igual, só sem os avisos). */
    private fun notificar(ctx: Context, id: Int, n: Notification) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.areNotificationsEnabled()) runCatching { nm.notify(id, n) }
    }
}
