// A tela que aparece por cima do app bloqueado (Instagram ou YouTube) quando ele está na frente, pra tirar a pessoa de
// lá de verdade, não só cortar a internet por baixo. É o que o Maverick do computador faz (a "Cover"). Usa a permissão
// "Aparecer sobre outros apps" (SYSTEM_ALERT_WINDOW), que é diferente de acessibilidade: o Nubank não implica com ela.
//
// No fundo, um carrossel de carros muito luxuosos (CARROS, fotos do Wikimedia Commons com licença livre, cada uma com o
// carro, o autor e a licença embaixo); por cima, um vídeo motivacional (VIDEOS: Elon Musk, Guto Galamba, O Lobo de Wall
// Street), o aviso e o botão que leva pra tela inicial. O vídeo toca pelo player oficial do YouTube, a partir do canal de
// quem publicou, a pelo menos 40% do volume de mídia; o volume de antes volta quando a tela some (o dono, 10-31).
//
// Só aparece sobre o app do site bloqueado (o escudo só manda mostrar quando esse app está na frente); nunca sobre um
// banco, o despertador ou qualquer outro. Sem a permissão, não faz nada.
package io.github.luiz_nast.maverick.truck

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class Cobertura(private val ctx: Context) {
    private val janelas = ctx.getSystemService(WindowManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var vista: View? = null
    private var foto: ImageView? = null
    private var legenda: TextView? = null
    private var titulo: TextView? = null
    private var linha: TextView? = null
    private var siteNaTela: Site? = null
    private var indice = 0

    private var video: WebView? = null
    private var volumeAntes: Int? = null // o volume de mídia de antes do vídeo, pra devolver
    private var volumeAlvo: Int? = null // o que o vídeo pôs: se a pessoa mexeu depois, o dela fica

    /** A próxima foto do carrossel, a cada [TROCA_MS], com um esmaecer rápido. */
    private val trocar = object : Runnable {
        override fun run() {
            val f = foto ?: return
            f.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                indice = (indice + 1) % CARROS.size
                mostrarFoto()
                f.animate().alpha(1f).setDuration(FADE_MS).start()
            }.start()
            handler.postDelayed(this, TROCA_MS)
        }
    }

    /** Mostra (ou atualiza) a tela por cima pro [site] bloqueado até [ate] (epoch, ms). Sem a permissão, não faz nada. */
    fun mostrar(site: Site, ate: Long) {
        if (Build.VERSION.SDK_INT < 23 || !Settings.canDrawOverlays(ctx)) return
        if (vista == null) {
            criar()
            if (vista == null) return
            handler.postDelayed(trocar, TROCA_MS)
            tocarVideo()
        }
        if (site != siteNaTela) {
            siteNaTela = site
            titulo?.text = "${site.nome} is blocked"
        }
        val hora = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ate).atZone(ZoneId.systemDefault()))
        linha?.text = "Your ${LIVRE_MS / 60_000} minutes are up.\nFree again at $hora."
    }

    /** Tira a tela, se estiver aparecendo; o vídeo para e o volume de antes volta. */
    fun esconder() {
        val v = vista ?: return
        vista = null
        foto = null
        legenda = null
        titulo = null
        linha = null
        siteNaTela = null
        handler.removeCallbacks(trocar)
        pararVideo()
        runCatching { janelas.removeView(v) }
    }

    /** O escudo acabou: tira a tela (e com ela o vídeo). */
    fun liberar() = esconder()

    private fun mostrarFoto() {
        val carro = CARROS[indice]
        foto?.setImageResource(carro.desenho)
        legenda?.text = carro.legenda
    }

    private fun criar() {
        val raiz = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // consome os toques: nada passa pro app atrás
        }
        foto = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } // o carro inteiro, não um pedaço ampliado
        raiz.addView(foto, FrameLayout.LayoutParams(-1, -1))
        // Escurece a foto, mais embaixo e no meio, pro texto ler bem por cima de qualquer carro
        raiz.addView(
            View(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#66000000"), Color.parseColor("#AA000000"), Color.parseColor("#DD000000")),
                )
            },
            FrameLayout.LayoutParams(-1, -1),
        )
        titulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTextColor(Color.parseColor("#FFF2F2F3"))
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        linha = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#FFE6E6E8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(32))
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val botao = Button(ctx).apply {
            text = "Leave"
            setTextColor(Color.parseColor("#FF0E0F12"))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#FFFF7A1A"))
            }
            setPadding(dp(48), dp(14), dp(48), dp(14))
            setOnClickListener { paraOInicio() }
        }
        // O vídeo, em 16:9 na largura da tela (menos as margens), em cima do aviso
        val largura = ctx.resources.displayMetrics.widthPixels - dp(32)
        video = runCatching { criarVideo() }
            .onFailure { Log.w(TAG, "Tela de bloqueio: sem o WebView, sem vídeo", it) }
            .getOrNull()
        val meio = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
            video?.let { v ->
                addView(v, LinearLayout.LayoutParams(largura, largura * 9 / 16))
                addView(TextView(ctx).apply {
                    text = "Clips play from the official YouTube channels of Paramount, USC, Lex Clips and oanimal.social"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(Color.parseColor("#99FFFFFF"))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(24))
                })
            }
            addView(titulo)
            addView(linha)
            addView(botao)
        }
        raiz.addView(meio, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        // O crédito da foto, embaixo: o carro, o autor e a licença (as licenças pedem a atribuição)
        legenda = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        raiz.addView(legenda, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        indice = (System.currentTimeMillis() / TROCA_MS % CARROS.size).toInt() // cada vez começa num carro
        raiz.addView(
            TextView(ctx).apply {
                text = CREDITOS
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(Color.parseColor("#80FFFFFF"))
                setPadding(dp(16), 0, dp(16), dp(8))
            },
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM),
        )
        mostrarFoto()
        val tipo = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            tipo,
            // Não rouba o foco do teclado, mas recebe os toques (o fundo os consome); por cima das barras do sistema; com a
            // placa de vídeo (o player do YouTube precisa)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        runCatching { janelas.addView(raiz, params) }
            .onSuccess { vista = raiz }
            .onFailure {
                Log.w(TAG, "Tela de bloqueio: o Android não deixou mostrar", it)
                video?.destroy()
                video = null
            }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun criarVideo(): WebView = WebView(ctx).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true // o player do YouTube é JavaScript
        settings.mediaPlaybackRequiresUserGesture = false // começa sozinho, com som
        webChromeClient = WebChromeClient()
    }

    /**
     * Começa os vídeos: o volume de mídia sobe pra pelo menos 40% e o player do YouTube toca a fila, cada vez começando
     * num clipe. Numa ligação, nada de vídeo nem de volume mexido.
     */
    private fun tocarVideo() {
        val v = video ?: return
        if (audio.mode != AudioManager.MODE_NORMAL) {
            Log.i(TAG, "Tela de bloqueio: ligação em andamento, sem vídeo")
            return
        }
        val maximo = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val atual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val alvo = volumeDosVideos(atual, maximo)
        if (alvo != atual) {
            volumeAntes = atual
            volumeAlvo = alvo
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, alvo, 0) }
        }
        val fila = filaDeVideos((System.currentTimeMillis() / 60_000).toInt())
        val endereco = "https://www.youtube.com/embed/${fila.first().id}?autoplay=1&playsinline=1&rel=0&loop=1" +
            "&playlist=${fila.drop(1).joinToString(",") { it.id }}&origin=$ORIGEM"
        val pagina = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            |<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
            |<body><iframe src="$endereco" allow="autoplay; encrypted-media; picture-in-picture"
            |referrerpolicy="strict-origin-when-cross-origin" allowfullscreen></iframe></body></html>""".trimMargin()
        // O YouTube pede uma origem de verdade pra tocar incorporado: a página do projeto no GitHub
        v.loadDataWithBaseURL("$ORIGEM/", pagina, "text/html", "utf-8", null)
    }

    /** O vídeo para (a tela sumiu) e o volume volta como estava. */
    private fun pararVideo() {
        video?.let {
            runCatching {
                it.loadUrl("about:blank")
                it.destroy()
            }
        }
        video = null
        val antes = volumeAntes
        // Só se o volume ainda é o que o vídeo pôs: se a pessoa mexeu (ou trocou pro fone), o dela fica
        if (antes != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volumeAlvo) {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, antes, 0) }
        }
        volumeAntes = null
        volumeAlvo = null
    }

    /** Vai pra tela inicial (tira o app bloqueado da frente). A tela por cima some no próximo tique, porque o app saiu da frente. */
    private fun paraOInicio() {
        val inicio = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(inicio) }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        /** De quanto em quanto tempo o carrossel troca de carro, e o esmaecer da troca. */
        const val TROCA_MS = 5_000L
        const val FADE_MS = 350L

        /** A origem que o player do YouTube vê (a página do projeto no GitHub). */
        const val ORIGEM = "https://luiz-nast.github.io"
    }
}
