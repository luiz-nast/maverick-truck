// A tela que aparece por cima do app bloqueado (Instagram ou YouTube) quando ele está na frente, pra tirar a pessoa de
// lá de verdade — não só cortar a internet por baixo. É o que o Maverick do computador faz (a "Cover"). Usa a permissão
// "Aparecer sobre outros apps" (SYSTEM_ALERT_WINDOW), que é diferente de acessibilidade: o Nubank não implica com ela.
//
// Só aparece sobre o app do site bloqueado (o escudo só manda mostrar quando esse app está na frente); nunca sobre um
// banco, o despertador ou qualquer outro. Tem um botão que leva pra tela inicial. Sem a permissão, não faz nada: o
// escudo segue cortando a rede.
package io.github.luiz_nast.maverick.truck

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class Cobertura(private val ctx: Context) {
    private val janelas = ctx.getSystemService(WindowManager::class.java)
    private var vista: View? = null
    private var titulo: TextView? = null
    private var linha: TextView? = null
    private var siteNaTela: Site? = null

    /** Mostra (ou atualiza) a tela por cima pro [site] bloqueado até [ate] (epoch, ms). Sem a permissão, não faz nada. */
    fun mostrar(site: Site, ate: Long) {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(ctx)) return
        if (vista == null) criar()
        if (site != siteNaTela) {
            siteNaTela = site
            titulo?.text = "${site.nome} is blocked"
        }
        val hora = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ate).atZone(ZoneId.systemDefault()))
        linha?.text = "Your ${LIVRE_MS / 60_000} minutes are up.\nFree again at $hora."
    }

    /** Tira a tela, se estiver aparecendo. */
    fun esconder() {
        val v = vista ?: return
        vista = null
        titulo = null
        linha = null
        siteNaTela = null
        runCatching { janelas.removeView(v) }
    }

    private fun criar() {
        val fundo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20E0F12"))
            setPadding(64, 64, 64, 64)
            isClickable = true // consome os toques: nada passa pro app atrás
        }
        titulo = TextView(ctx).apply {
            textSize = 30f
            setTextColor(Color.parseColor("#FFF2F2F3"))
            gravity = Gravity.CENTER
        }
        linha = TextView(ctx).apply {
            textSize = 18f
            setTextColor(Color.parseColor("#FF8A8F98"))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 64)
        }
        val botao = Button(ctx).apply {
            text = "Leave"
            setTextColor(Color.parseColor("#FF0E0F12"))
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#FFFF7A1A"))
            }
            setPadding(96, 32, 96, 32)
            setOnClickListener { paraOInicio() }
        }
        fundo.addView(titulo)
        fundo.addView(linha)
        fundo.addView(botao)
        val tipo = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            tipo,
            // Não rouba o foco do teclado, mas recebe os toques (o fundo os consome); por cima das barras do sistema
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.OPAQUE,
        )
        runCatching { janelas.addView(fundo, params) }.onSuccess { vista = fundo }
    }

    /** Vai pra tela inicial (tira o app bloqueado da frente). A tela por cima some no próximo tique, porque o app saiu da frente. */
    private fun paraOInicio() {
        val inicio = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(inicio) }
    }
}
