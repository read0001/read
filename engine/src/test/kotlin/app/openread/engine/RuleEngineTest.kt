package app.openread.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleEngineTest {
    private val http = Http()
    private fun rule(content: Any, base: String = "https://example.com/a/") =
        AnalyzeRule(RuleData(), null, http).setContent(content, base)

    private val html = """
        <html><body>
        <div class="list"><ul>
          <li><a href="/b/1.html" class="t">第一本</a><span class="au">作者：甲</span></li>
          <li><a href="/b/2.html" class="t">第二本</a><span class="au">作者：乙</span></li>
          <li><a href="/b/3.html" class="t">第三本</a><span class="au">作者：丙</span></li>
        </ul></div>
        <div id="content">第一段<br>第二段<br/>第三段</div>
        </body></html>
    """.trimIndent()

    @Test fun lenientJsonAcceptsSingleQuotes() {
        val o = JsonCodec.parse("{'method':'post','body':'a={{key}}&b=1'}")
        assertNotNull(o, "single-quoted option JSON must parse")
        val obj = o as kotlinx.serialization.json.JsonObject
        assertEquals("post", obj.str("method"))
        assertEquals("a={{key}}&b=1", obj.str("body"))
        val web = AnalyzeUrl("/s.php,{'method':'post','body':'k={{key}}&x=1'}", key = "斗破", source = null, http = http, baseUrl = "https://e.com")
        assertEquals("POST https://e.com/s.php body=k=斗破&x=1", web.describe())
    }

    @Test fun jsoupDefaultRules() {
        val ar = rule(html)
        val items = ar.getElements("class.list@tag.li")
        assertEquals(3, items.size)
        val first = rule(items[0])
        assertEquals("第一本", first.getString("class.t@text"))
        assertEquals("https://example.com/b/1.html", first.getString("tag.a@href", isUrl = true))
        assertEquals("甲", first.getString("class.au@text##作者：|\\s"))
        assertEquals("第三本", rule(html).getString("class.list@tag.li.2@tag.a@text"))
        assertEquals("第二本", rule(html).getString("class.list@tag.li[1]@tag.a@text"))
        assertEquals(2, rule(html).getElements("class.list@tag.li!0").size)
    }

    @Test fun cssAndXpathModes() {
        assertEquals("第二本", rule(html).getString("@css:.list li:nth-child(2) a@text"))
        assertEquals("第三本", rule(html).getString("//div[@class='list']//li[3]/a/text()"))
        assertEquals(3, rule(html).getElements("//div[@class='list']//li").size)
    }

    @Test fun jsonPathMode() {
        val json = """{"data":{"list":[{"name":"甲","id":1},{"name":"乙","id":2}]}}"""
        val ar = rule(json)
        val items = ar.getElements("$.data.list[*]")
        assertEquals(2, items.size)
        assertEquals("乙", rule(items[1]).getString("name"))
        assertEquals("2", rule(items[1]).getString("$.id"))
        assertEquals("乙-2", rule(items[1]).getString("{\$.name}-{\$.id}"))
    }

    @Test fun templatesJsAndReplace() {
        val ar = rule(html)
        assertEquals("x第一本y", ar.getString("x{{@css:.list li:nth-child(1) .t@text}}y"))
        assertEquals("3", ar.getString("@js:1+2"))
        assertEquals("ABC", ar.getString("@css:#content@ownText@js:result.length>0?'ABC':''"))
        assertEquals("第一段\n第二段\n第三段", HtmlFormatter.format(ar.getString("id.content@html")))
        assertEquals("abc", rule("hello").getString("##hello##abc"))
    }

    @Test fun operators() {
        val ar = rule(html)
        assertEquals("第一本\n第二本", ar.getString("class.t.0@text&&class.t.1@text"))
        assertEquals("第一本", ar.getString("class.none@text||class.t.0@text"))
    }

    @Test fun jsSandboxBlocksDangerousClasses() {
        val r = runCatching { JsRuntime.eval("java.lang.Runtime.getRuntime().exec('true')", emptyMap()) }
        assertTrue(r.isFailure, "Runtime must not be reachable from source JS")
        val s = runCatching { JsRuntime.eval("new java.io.File('/tmp').list()", emptyMap()) }
        assertTrue(s.isFailure, "java.io must not be reachable from source JS")
        assertEquals("ok", JsRuntime.eval("var l = new java.util.ArrayList(); l.add('o'); l.get(0) + 'k'", emptyMap()))
    }

    @Test fun urlOptionsAndTemplates() {
        val src = BookSource.parseList("""{"bookSourceUrl":"https://example.com","bookSourceName":"t","searchUrl":"/s?q={{key}}&p={{page}}"}""").first()
        val u = AnalyzeUrl(src.searchUrl!!, key = "斗破", page = 2, source = src, http = http)
        assertEquals("https://example.com/s?q=%E6%96%97%E7%A0%B4&p=2", u.url)
        val gbk = AnalyzeUrl("/s?q={{key}},{\"charset\":\"gbk\"}", key = "斗破", source = src, http = http)
        assertEquals("https://example.com/s?q=%B6%B7%C6%C6", gbk.url)
        val paged = AnalyzeUrl("/list/<1,2,3>.html", page = 5, source = src, http = http)
        assertEquals("https://example.com/list/3.html", paged.url)
        val js = AnalyzeUrl("/p/{{(page-1)*20}}", page = 3, source = src, http = http)
        assertEquals("https://example.com/p/40", js.url)
    }

    @Test fun queryOnlyRelativeLinkKeepsThePath() {
        assertEquals("https://example.com/a/b.html?page=2", UrlUtil.absolute("https://example.com/a/b.html?x=1", "?page=2"))
        assertEquals("https://example.com/c.html", UrlUtil.absolute("https://example.com/a/b.html", "/c.html"))
    }

    @Test fun rangeIndexOnNoMatchIsEmptyNotACrash() {
        assertEquals(0, rule(html).getElements("class.zzz[0:]").size)
        assertEquals(emptyList(), rule(html).getStringList("class.zzz[1:]@text"))
    }

    @Test fun getQueryValuesAreEncodedPerField() {
        val u = AnalyzeUrl("/s?q={{key}}&t=1", key = "C++ a#b", baseUrl = "https://e.com", http = http)
        assertEquals("https://e.com/s?q=C%2B%2B+a%23b&t=1", u.url)
    }

    @Test fun jsLibFunctionsSeeTheBridgeObjects() {
        val src = BookSource.parseList("""{"bookSourceUrl":"https://e.com","bookSourceName":"t","jsLib":"function sign(x){ return java.md5Encode(x) }"}""").first()
        val ar = AnalyzeRule(RuleData(), src, http).setContent("x", "https://e.com")
        assertEquals("0cc175b9c0f1b6a831c399e269772661", ar.getString("@js:sign('a')"))
    }

    @Test fun regexAllInOneFillsFieldRulesFromGroups() {
        val ar = AnalyzeRule(RuleData(), null, http).setContent("1:foo\n2:bar\n", "https://e.com")
        val items = ar.getElements(":(\\d):(\\w+)")
        assertEquals(2, items.size)
        assertEquals("bar", ar.child(items[1], RuleData()).getString("$2"))
    }

    @Test fun runawayRecursionEndsAsAScriptError() {
        val r = runCatching { JsRuntime.eval("function f(n){return f(n+1)+1} f(0)", emptyMap()) }
        assertTrue(r.isFailure)
    }

    @Test fun engineInternalsAreNotReachableFromScripts() {
        val r = runCatching { JsRuntime.eval("new Packages.app.openread.engine.Http(null, null, 5, null)", emptyMap()) }
        assertTrue(r.isFailure, "Http must not be constructible from source JS")
    }

    @Test fun tagsAreCleaned() {
        assertEquals("少年,斗气", WebBook.tags(listOf("9.1分".let { "" }, ",,少年", "斗气,少年")))
    }

    @Test fun lessThanInTextSurvivesFormatting() {
        assertEquals("我 < 你，你 > 我", HtmlFormatter.format("我 < 你，你 > 我"))
        assertEquals("a\nb", HtmlFormatter.format("a<br>b"))
    }

    private fun server(vararg routes: Pair<String, String>, delayMs: Long = 0): com.sun.net.httpserver.HttpServer {
        val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        routes.forEach { (path, body) ->
            srv.createContext(path) { ex ->
                if (delayMs > 0) Thread.sleep(delayMs)
                val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
        }
        srv.start()
        return srv
    }

    @Test fun updateCheckSeesEveryMirrorAndTheCallerPicksTheNewest() {
        val stale = server("/v.json" to """{"versionCode":2}""")
        val fresh = server("/v.json" to """{"versionCode":5}""", delayMs = 400)   // answers later, but is the newer copy
        val missing = server()                                                       // 404: has no such file
        try {
            fun url(s: com.sun.net.httpserver.HttpServer) = "http://127.0.0.1:${s.address.port}/v.json"
            val a = kotlinx.coroutines.runBlocking { Mirrors.fetchAllFrom(http, listOf(url(stale), url(fresh), url(missing))) { it.contains("versionCode") } }
            val newest = a.bodies.maxOf { Regex("""versionCode":(\d+)""").find(it)!!.groupValues[1].toInt() }
            assertEquals(5, newest, "a stale mirror that answers first must not hide the newer release")
            assertEquals(null, a.error, "404 on one mirror is not an error")
        } finally { stale.stop(0); fresh.stop(0); missing.stop(0) }
    }

    @Test fun downloadComesFromTheFastestMirrorAndSkipsDeadOnes() {
        val slow = server("/a.apk" to "SLOW", delayMs = 1500)
        val fast = server("/a.apk" to "FAST")
        val dead = java.net.ServerSocket(0).use { it.localPort }                      // nothing listens here
        try {
            val urls = listOf("http://127.0.0.1:$dead/a.apk", "http://127.0.0.1:${slow.address.port}/a.apk", "http://127.0.0.1:${fast.address.port}/a.apk")
            val t0 = System.nanoTime()
            val body = kotlinx.coroutines.runBlocking { Mirrors.openFirst(http.client, urls).use { it.body.string() } }
            assertEquals("FAST", body)
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 1200, "must not wait for the slow or dead mirror")
        } finally { slow.stop(0); fast.stop(0) }
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/douban/$name")) { name }.readText()

    @Test fun doubanSearchPicksTheNovelNotAVolumeOrASeries() {
        val subjects = Douban.parseSearch(fixture("search.html"))
        assertTrue(subjects.none { it.title.contains("丛书") }, "a series has no /subject/ page")
        val hit = Douban.pick(subjects, "斗破苍穹", "天蚕土豆_qd22")
        assertEquals("22933018", hit?.id)
        assertTrue((hit?.ratingCount ?: 0) > 1000)
        assertEquals(null, Douban.pick(subjects, "斗破苍穹", "某个不相干的作者"), "wrong author: show nothing rather than another book's comments")
        assertEquals(null, Douban.pick(subjects, "完全不存在的书名", ""))
    }

    @Test fun doubanShortCommentsAndReviewsParse() {
        val page = Douban.parseInterests(fixture("interests.json"))
        assertTrue(page.total > 1000)
        assertEquals(3, page.items.size)
        assertTrue(page.items.all { it.text.isNotBlank() && it.user.startsWith("读者") && it.time.length == 10 })
        assertTrue(page.items.first().stars in 1..5)
        val reviews = Douban.parseReviews(fixture("reviews.json"))
        assertEquals(2, reviews.items.size)
        assertTrue(reviews.items.all { !it.title.isNullOrBlank() && it.url?.startsWith("https://book.douban.com/review/") == true })
    }

    @Test fun doubanLoginWallIsAReadableError() {
        val e = runCatching { Douban.parseInterests("""{"request":"GET /v2/book/1/interests","msg":"need_permission","code":1000}""") }.exceptionOrNull()
        assertTrue(e is DoubanException && e.message!!.contains("登录"))
    }
}
