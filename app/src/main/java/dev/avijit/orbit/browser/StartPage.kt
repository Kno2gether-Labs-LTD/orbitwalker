package dev.avijit.orbit.browser

/** Orbit Browser's new-tab page: a search box and a few shortcuts. Static HTML, no scripts. */
object StartPage {
    private val SHORTCUTS = listOf(
        "The Orbit Apps" to "https://orbitapps.space/",
        "YouTube" to "https://www.youtube.com/",
        "Wikipedia" to "https://www.wikipedia.org/",
        "GitHub" to "https://github.com/",
    )

    fun html(engine: SearchEngine): String {
        val action = engine.formAction
        val tiles = SHORTCUTS.joinToString("") { (name, url) -> """<a class="tile" href="$url"><b>${name.first()}</b><span>$name</span></a>""" }
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>New tab</title><style>
html,body{margin:0;height:100%;background:#0a0820;color:#e8e6ff;font-family:system-ui,sans-serif}
main{min-height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:28px;padding:24px;box-sizing:border-box}
h1{margin:0;font-size:34px;font-weight:800;letter-spacing:-.5px}h1 em{font-style:normal;font-weight:300;color:#7df5d6}
form{width:min(640px,100%)}input{width:100%;box-sizing:border-box;padding:16px 22px;border-radius:999px;border:1px solid #3a3380;background:#1c1742;color:#fff;font-size:18px;outline:none}
input:focus{border-color:#7df5d6}.tiles{display:flex;gap:14px;flex-wrap:wrap;justify-content:center}
.tile{display:flex;flex-direction:column;align-items:center;gap:8px;width:110px;padding:16px 8px;border-radius:16px;background:#16123a;color:#cfcaf5;text-decoration:none;font-size:13px}
.tile b{display:grid;place-items:center;width:44px;height:44px;border-radius:50%;background:linear-gradient(135deg,#7df5d6,#6f8cff,#a98bff);color:#0a0820;font-size:20px}
.tile:hover{background:#221c55}p{margin:0;color:#8f89c4;font-size:13px}
</style></head><body><main>
<h1>Orbit <em>Browser</em></h1>
<form action="$action" method="get"><input name="q" placeholder="Search with ${engine.label} or type a web address" autocomplete="off"></form>
<div class="tiles">$tiles</div>
<p>Tip: drag a tab down out of the tab strip to give it its own screen.</p>
</main></body></html>"""
    }
}
