package dev.avijit.orbit.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import dev.avijit.orbit.R
import dev.avijit.orbit.ScreensSession
import dev.avijit.orbit.diag.DiagLog

/**
 * Orbit Browser: a Chromium (System WebView) browser that runs as its own screen in the glasses,
 * or on the phone. Each screen has its own tabs; a tab dragged down out of the strip moves, page
 * and all, onto a new screen. Never logs URLs or anything typed.
 */
class BrowserActivity : ComponentActivity() {

    private val tabs = TabList<BrowserTab>()
    private lateinit var strip: LinearLayout
    private lateinit var address: EditText
    private lateinit var progress: ProgressBar
    private lateinit var content: FrameLayout
    private lateinit var fullscreen: FrameLayout
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var popBtn: ImageButton
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs by lazy { getSharedPreferences("browser", Context.MODE_PRIVATE) }
    private var engine: SearchEngine
        get() = runCatching { SearchEngine.valueOf(prefs.getString(KEY_ENGINE, null)!!) }.getOrDefault(SearchEngine.DUCKDUCKGO)
        set(v) { prefs.edit().putString(KEY_ENGINE, v.name).apply() }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    /** True on a glasses screen: tabs can then move onto screens of their own. */
    private val onGlasses: Boolean get() = (display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BAR
        window.navigationBarColor = BG
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBackOrClose()
        })

        val request = display?.displayId?.let { BrowserHub.take(it) }
        val adopted = request?.adoptTab?.let { BrowserHub.unpark(it) }
        if (adopted != null) {
            adopted.context.baseContext = this
            attach(adopted)
            tabs.add(adopted.id, adopted)
            showSelected()
        } else {
            newTab(request?.url)
        }
        DiagLog.i(TAG, "browser screen opened (glasses=$onGlasses, moved tab=${adopted != null})")
    }

    override fun onStart() { super.onStart(); tabs.ids.forEach { tabs[it]?.web?.onResume() } }
    override fun onStop() { super.onStop(); tabs.ids.forEach { tabs[it]?.web?.onPause() } }

    override fun onDestroy() {
        tabs.ids.forEach { id -> tabs.close(id)?.web?.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tabs

    private fun newTab(url: String?, select: Boolean = true): BrowserTab {
        val ctx = MutableContextWrapper(this)
        val tab = BrowserTab(BrowserHub.newTabId(), ctx, WebView(ctx))
        configure(tab)
        attach(tab)
        tabs.add(tab.id, tab, select)
        if (url != null) tab.web.loadUrl(url) else loadStartPage(tab)
        if (select) showSelected() else renderStrip()
        if (select) { if (url == null) { address.setText(""); focusAddress() } else tab.web.requestFocus() }
        return tab
    }

    private fun selectedTab(): BrowserTab? = tabs.selected?.let { tabs[it] }

    private fun closeTab(id: Long) {
        val wasLast = tabs.size == 1
        tabs.close(id)?.web?.let { content.removeView(it); it.destroy() }
        if (wasLast) finish() else showSelected()
    }

    /** Moves tab [id] onto a new glasses screen; the page keeps running as it moves. */
    private fun popOut(id: Long) {
        if (!onGlasses) return toast("Open Orbit Browser in the glasses to give a tab its own screen")
        val tab = tabs.close(id) ?: return
        content.removeView(tab.web)
        BrowserHub.park(tab)
        ScreensSession.get(this).openBrowser(adoptTab = tab.id)
        if (tabs.isEmpty) finish() else showSelected()
    }

    private fun showSelected() {
        val tab = selectedTab() ?: return
        // Only the selected tab is in the view tree; the others keep their pages off-screen.
        if (content.childCount != 1 || content.getChildAt(0) !== tab.web) {
            content.removeAllViews()
            (tab.web.parent as? ViewGroup)?.removeView(tab.web)
            content.addView(tab.web, FrameLayout.LayoutParams(-1, -1))
        }
        renderStrip()
        renderToolbar(switched = true)
    }

    private fun renderStrip() {
        strip.removeAllViews()
        tabs.ids.forEach { id -> tabs[id]?.let { strip.addView(tabChip(it, id == tabs.selected)) } }
        strip.addView(iconButton(R.drawable.ic_br_add, "New tab") { newTab(null); focusAddress() })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun tabChip(tab: BrowserTab, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (selected) CHIP_ON else CHIP_OFF, dp(10f))
            setPadding(dp(12), 0, dp(4), 0)
        }
        row.addView(TextView(this).apply {
            text = tab.title.ifBlank { UrlInput.display(tab.url).ifBlank { "New tab" } }
            setTextColor(if (selected) TEXT else TEXT_DIM)
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(180)
        })
        row.addView(iconButton(R.drawable.ic_br_close, "Close tab", small = true) { closeTab(tab.id) })
        row.layoutParams = LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(6) }
        // Click selects; drag down out of the strip moves the tab onto its own screen.
        var downX = 0f; var downY = 0f; var dragging = false
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (dy > dp(8) && dy > kotlin.math.abs(e.rawX - downX)) {
                        dragging = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        v.translationY = dy.coerceAtMost(dp(120).toFloat())
                        v.alpha = if (onGlasses && TabDrag.popsOut(e.rawX - downX, dy, stripHeight())) 0.55f else 1f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.translationY = 0f; v.alpha = 1f
                    if (dragging && TabDrag.popsOut(e.rawX - downX, e.rawY - downY, stripHeight())) popOut(tab.id)
                    else if (!dragging) { tabs.select(tab.id); showSelected() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { v.translationY = 0f; v.alpha = 1f; true }
                else -> false
            }
        }
        return row
    }

    private fun stripHeight(): Float = (strip.parent as View).height.toFloat().coerceAtLeast(dp(40).toFloat())

    // ---------------------------------------------------------------- web views

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(tab: BrowserTab) = with(tab.web.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
        builtInZoomControls = true
        displayZoomControls = false
        loadWithOverviewMode = true
        useWideViewPort = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        allowFileAccess = false
        allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(tab.web, false)
        tab.web.setBackgroundColor(BG)
    }

    /** (Re)binds a tab's callbacks to this screen, e.g. after it moved here from another one. */
    private fun attach(tab: BrowserTab) {
        val web = tab.web
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleSpecialScheme(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tab.url = url
                if (tab.id == tabs.selected) renderToolbar()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                tab.url = url
                if (tab.id == tabs.selected) renderToolbar()
                renderStrip()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tab.id != tabs.selected) return
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onReceivedTitle(view: WebView, title: String?) { tab.title = title.orEmpty(); renderStrip() }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false // no pop-ups the user didn't ask for
                val t = newTab(url = "about:blank")
                (resultMsg.obj as WebView.WebViewTransport).webView = t.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.ids.firstOrNull { tabs[it]?.web === window }?.let { closeTab(it) }
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return runCatching { pickFiles.launch(params.createIntent()) }.isSuccess.also { if (!it) fileCallback = null }
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView?.let { hideCustomView() }
                customView = view; customCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.visibility = View.VISIBLE
            }

            override fun onHideCustomView() = hideCustomView()

            // Private by default: sites don't get the camera, microphone or location.
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
        }
        web.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
        web.setOnCreateContextMenuListener { menu, _, _ ->
            val hit = web.hitTestResult
            val link = hit.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && URLUtil.isNetworkUrl(it) }
                ?: return@setOnCreateContextMenuListener
            menu.add("Open in new tab").setOnMenuItemClickListener { newTab(link, select = false); true }
            if (onGlasses) menu.add("Open in new screen").setOnMenuItemClickListener {
                ScreensSession.get(this).openBrowser(url = link); true
            }
            menu.add("Copy link").setOnMenuItemClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", link)); true
            }
        }
    }

    /** Non-web links (mail, phone, store, intent://) go to the right app, or are ignored. Returns true if handled. */
    private fun handleSpecialScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
        if (scheme == "intent") {
            // Only follow a page's own web fallback; never launch arbitrary components from a page.
            val fallback = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
            if (fallback != null && URLUtil.isNetworkUrl(fallback)) selectedTab()?.web?.loadUrl(fallback)
            return true
        }
        if (scheme in setOf("mailto", "tel", "sms", "geo", "market")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                .onFailure { toast("No app can open this link") }
        }
        return true
    }

    private fun hideCustomView() {
        fullscreen.removeAllViews()
        fullscreen.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customView = null; customCallback = null
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (!URLUtil.isNetworkUrl(url)) return toast("This file can't be downloaded")
        val name = URLUtil.guessFileName(url, disposition, mime)
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            getSystemService(DownloadManager::class.java).enqueue(req)
        }.onSuccess { toast("Downloading $name") }.onFailure { toast("Download failed") }
    }

    private fun loadStartPage(tab: BrowserTab) {
        tab.title = "New tab"
        tab.web.loadDataWithBaseURL(null, StartPage.html(engine), "text/html", "utf-8", null)
    }

    private fun setDesktop(tab: BrowserTab, on: Boolean) {
        tab.desktop = on
        val s = tab.web.settings
        s.userAgentString = if (on) {
            WebSettings.getDefaultUserAgent(this)
                .replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)")
                .replace(" Mobile", "")
        } else null
        tab.web.reload()
    }

    // ---------------------------------------------------------------- toolbar

    /** [switched]: another tab was just selected, so the address bar shows its page even while focused. */
    private fun renderToolbar(switched: Boolean = false) {
        val tab = selectedTab() ?: return
        if (switched || !address.hasFocus()) address.setText(UrlInput.display(tab.url))
        backBtn.isEnabled = tab.web.canGoBack(); backBtn.alpha = if (backBtn.isEnabled) 1f else 0.35f
        forwardBtn.isEnabled = tab.web.canGoForward(); forwardBtn.alpha = if (forwardBtn.isEnabled) 1f else 0.35f
        popBtn.visibility = if (onGlasses) View.VISIBLE else View.GONE
    }

    private fun go(typed: String) {
        val url = UrlInput.resolve(typed, engine) ?: return
        val tab = selectedTab() ?: newTab(url).also { return }
        tab.web.loadUrl(url)
        address.clearFocus()
        tab.web.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(address.windowToken, 0)
    }

    private fun reload() {
        val tab = selectedTab() ?: return
        if (tab.url == null || tab.url == "about:blank") loadStartPage(tab) else tab.web.reload()
    }

    /** Ready to type with a keyboard; the on-screen keyboard only appears when the field is clicked. */
    private fun focusAddress() {
        address.requestFocus()
        address.selectAll()
    }

    private fun goBackOrClose() {
        when {
            customView != null -> hideCustomView()
            selectedTab()?.web?.canGoBack() == true -> selectedTab()!!.web.goBack()
            tabs.size > 1 -> tabs.selected?.let { closeTab(it) }
            else -> finish()
        }
    }

    private fun showMenu(anchor: View) {
        val tab = selectedTab() ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "New tab")
            if (onGlasses) menu.add(0, 2, 0, "Move tab to its own screen")
            menu.add(0, 3, 0, "Desktop site").setCheckable(true).setChecked(tab.desktop)
            val engines = menu.addSubMenu(0, 4, 0, "Search with ${engine.label}")
            SearchEngine.entries.forEachIndexed { i, e -> engines.add(1, 100 + i, i, e.label).setCheckable(true).setChecked(e == engine) }
            menu.add(0, 5, 0, "Copy page link")
            menu.add(0, 6, 0, "Close this screen")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { newTab(null); focusAddress() }
                    2 -> popOut(tab.id)
                    3 -> setDesktop(tab, !tab.desktop)
                    5 -> tab.url?.takeIf { URLUtil.isNetworkUrl(it) }?.let {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", it)); toast("Link copied")
                    }
                    6 -> finish()
                    in 100 until 100 + SearchEngine.entries.size -> engine = SearchEngine.entries[item.itemId - 100]
                }
                true
            }
        }.show()
    }

    // ---------------------------------------------------------------- keyboard

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleShortcut(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleShortcut(e: KeyEvent): Boolean {
        val tab = selectedTab()
        if (e.isCtrlPressed) when (e.keyCode) {
            KeyEvent.KEYCODE_T -> { newTab(null); focusAddress(); return true }
            KeyEvent.KEYCODE_W -> { tabs.selected?.let { closeTab(it) }; return true }
            KeyEvent.KEYCODE_L -> { focusAddress(); return true }
            KeyEvent.KEYCODE_R -> { reload(); return true }
            KeyEvent.KEYCODE_TAB -> { tabs.next(); showSelected(); return true }
        }
        if (e.isAltPressed) when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { tab?.web?.takeIf { it.canGoBack() }?.goBack(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { tab?.web?.takeIf { it.canGoForward() }?.goForward(); return true }
        }
        if (e.keyCode == KeyEvent.KEYCODE_F5) { reload(); return true }
        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE && address.hasFocus()) {
            address.clearFocus(); renderToolbar(); tab?.web?.requestFocus(); return true
        }
        return false
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        column.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(BAR)
            addView(strip)
        }, LinearLayout.LayoutParams(-1, -2))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR); setPadding(dp(4), 0, dp(4), dp(6))
        }
        backBtn = iconButton(R.drawable.ic_br_back, "Back") { selectedTab()?.web?.goBack() }
        forwardBtn = iconButton(R.drawable.ic_br_forward, "Forward") { selectedTab()?.web?.goForward() }
        toolbar.addView(backBtn); toolbar.addView(forwardBtn)
        toolbar.addView(iconButton(R.drawable.ic_br_refresh, "Reload") { reload() })
        address = EditText(this).apply {
            hint = "Search or type a web address"
            setHintTextColor(TEXT_DIM); setTextColor(TEXT); textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(FIELD, dp(18f))
            setPadding(dp(16), 0, dp(16), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { v, action, ev ->
                val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_GO || enter) { go(v.text.toString()); true } else false
            }
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        popBtn = iconButton(R.drawable.ic_br_popout, "Move tab to its own screen") { tabs.selected?.let { popOut(it) } }
        toolbar.addView(popBtn)
        toolbar.addView(iconButton(R.drawable.ic_br_more, "Menu") { showMenu(it) })
        column.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.INVISIBLE
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        column.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        fullscreen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun iconButton(icon: Int, label: String, small: Boolean = false, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = label
        tooltipText = label
        background = null
        // Mouse-only, like a desktop browser's buttons: keys (Enter, Space) must never press "close tab".
        isFocusable = false
        val s = if (small) dp(26) else dp(40)
        layoutParams = LinearLayout.LayoutParams(s, s)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(if (small) 5 else 8), dp(if (small) 5 else 8), dp(if (small) 5 else 8), dp(if (small) 5 else 8))
        setOnClickListener(onClick)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "OrbitBrowser"
        private const val KEY_ENGINE = "engine"
        private val BG = Color.rgb(10, 8, 32)
        private val BAR = Color.rgb(20, 16, 51)
        private val FIELD = Color.rgb(35, 30, 80)
        private val CHIP_ON = Color.rgb(48, 40, 110)
        private val CHIP_OFF = Color.rgb(28, 23, 66)
        private val TEXT = Color.rgb(232, 230, 255)
        private val TEXT_DIM = Color.rgb(150, 145, 200)
        private val ACCENT = Color.rgb(125, 245, 214)
    }
}
