// A plaquinha flutuante do tempo, igual à do Maverick do computador: enquanto o Instagram ou o YouTube está na frente
// (e ainda não bloqueou), um selo pequeno mostra quanto dos 7 min já foi e quanto falta, mudando de verde pra vermelho
// conforme enche. Dá pra arrastar pra qualquer canto (o dono, 10-31), e ele lembra onde ficou. Usa a mesma permissão da
// tela de bloqueio ("Aparecer sobre outros apps"), não é acessibilidade. Só sobre o Instagram e o YouTube (o escudo só
// manda mostrar quando um deles está na frente): nunca sobre banco ou outro app.
package io.github.luiz_nast.maverick.truck

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class Medidor(private val ctx: Context) {
    private val janelas = ctx.getSystemService(WindowManager::class.java)
    private val prefs = ctx.getSharedPreferences("medidor", Context.MODE_PRIVATE)
    private var selo: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Mostra (ou atualiza) o selo pro [site] na frente: [usadoMs] de [totalMs]. Sem a permissão, não faz nada. */
    fun mostrar(site: Site, usadoMs: Long, totalMs: Long) {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(ctx)) return
        if (selo == null) criar()
        val fracao = if (totalMs > 0) usadoMs.toFloat() / totalMs else 0f
        val cor = when {
            fracao < 0.6f -> Color.parseColor("#FF3DD68C") // verde
            fracao < 0.85f -> Color.parseColor("#FFFF7A1A") // laranja
            else -> Color.parseColor("#FFE5484D") // vermelho
        }
        selo?.apply {
            text = "${site.nome}  ${mmss(usadoMs)} / ${mmss(totalMs)}"
            (background as? GradientDrawable)?.setStroke(dp(1.5f), cor)
            setTextColor(cor)
        }
    }

    /** Tira o selo, se estiver aparecendo (a posição onde ele foi largado fica guardada). */
    fun esconder() {
        val v = selo ?: return
        selo = null
        params = null
        runCatching { janelas.removeView(v) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun criar() {
        val t = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(Color.parseColor("#E60E0F12")) // fundo escuro quase opaco
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            },
            // Pega toque só nele mesmo (pra arrastar); não rouba o foco do teclado. O resto da tela segue livre
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val largura = ctx.resources.displayMetrics.widthPixels
            x = prefs.getInt("x", (largura - dp(170f)) / 2).coerceAtLeast(0)
            y = prefs.getInt("y", dp(72f))
        }
        params = lp
        t.setOnTouchListener(arrastar())
        runCatching { janelas.addView(t, lp) }.onSuccess { selo = t }
    }

    /** Arrasta o selo: a janela segue o dedo; ao soltar, a posição fica guardada. Um toque parado não faz nada (é só mostrador). */
    private fun arrastar(): View.OnTouchListener {
        var baseX = 0
        var baseY = 0
        var toqueX = 0f
        var toqueY = 0f
        return View.OnTouchListener { _, e ->
            val lp = params ?: return@OnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    baseX = lp.x
                    baseY = lp.y
                    toqueX = e.rawX
                    toqueY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val m = ctx.resources.displayMetrics
                    val v = selo ?: return@OnTouchListener false
                    lp.x = (baseX + (e.rawX - toqueX).toInt()).coerceIn(0, (m.widthPixels - v.width).coerceAtLeast(0))
                    lp.y = (baseY + (e.rawY - toqueY).toInt()).coerceIn(0, (m.heightPixels - v.height).coerceAtLeast(0))
                    runCatching { janelas.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - toqueX) > dp(4f) || abs(e.rawY - toqueY) > dp(4f)) {
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Float): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
