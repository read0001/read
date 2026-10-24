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

    private fun chap(vararg titles: String) = titles.mapIndexed { i, t -> Chapter(i, t, "https://e.com/$i-$t") }

    @Test fun chapterNumbersReadChineseAndArabicNumerals() {
        assertEquals(1593, ChapterNumbers.of("第一千五百九十三章 古帝洞府所在"))
        assertEquals(832, ChapterNumbers.of("第832章 莫崖 【第一更！】"))
        assertEquals(10, ChapterNumbers.of("第十章 借钱"))
        assertEquals(1010, ChapterNumbers.of("第一千零一十章 x"))
        assertEquals(null, ChapterNumbers.of("《斗破苍穹：斗帝之路》手游·角色传记（下）"))
    }

    @Test fun latestChaptersBlockInFrontMovesToTheEnd() {
        val head = listOf("第1593章 新", "第1464章 d", "第1463章 c", "第1462章 b")
        val main = (1..12).map { "第${it}章 x$it" }
        val fixed = TocFix.apply(chap(*(head + main).toTypedArray()))
        assertEquals("第1章 x1", fixed.first().title)
        assertEquals(listOf("第1462章 b", "第1463章 c", "第1464章 d", "第1593章 新"), fixed.takeLast(4).map { it.title })
    }

    @Test fun wholeListInDescendingOrderIsReversedAndAscendingIsLeftAlone() {
        val desc = chap(*(20 downTo 1).map { "第${it}章" }.toTypedArray())
        assertEquals("第1章", TocFix.apply(desc).first().title)
        val asc = chap(*(1..20).map { "第${it}章" }.toTypedArray())
        assertEquals(asc.map { it.title }, TocFix.apply(asc).map { it.title })
        val unnumbered = chap(*(1..20).map { "序$it" }.toTypedArray())
        assertEquals(unnumbered.map { it.title }, TocFix.apply(unnumbered).map { it.title })
    }

    private fun tocSource(pages: Int, nextRule: String?): Pair<BookSource, com.sun.net.httpserver.HttpServer> {
        val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        for (p in 1..pages) srv.createContext("/toc$p") { ex ->
            val items = (1..5).joinToString("") { n -> val c = (p - 1) * 5 + n; "<li><a href='/c/$c'>第${c}章</a></li>" }
            val nav = if (p < pages) "<a href='/toc${p + 1}'>下一页</a>" else ""
            val b = "<html><body><ul>$items</ul><div id='pages'>第$p/$pages 页</div>$nav</body></html>".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"
        val next = nextRule?.let { ""","nextTocUrl":${kotlinx.serialization.json.JsonPrimitive(it.replace("BASE", base))}""" }.orEmpty()
        val json = """{"bookSourceUrl":"$base","bookSourceName":"t","ruleToc":{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"$next}}"""
        return BookSource.parseList(json).first() to srv
    }

    @Test fun tocFollowsAPlainNextLinkWhenTheRuleHasNone() {
        val (src, srv) = tocSource(4, null)
        try {
            val book = Book("${src.baseUrl}/toc1", "${src.baseUrl}/toc1", src.key, "t", "n", "a")
            val trace = ArrayList<String>()
            val toc = WebBook(src, http).chapters(book, trace)
            assertEquals(20, toc.size, "all four pages")
            assertEquals(4, trace.size)
        } finally { srv.stop(0) }
    }

    @Test fun tocRuleThatListsEveryPageIsFetchedAllAtOnce() {
        // Page 1 yields the addresses of pages 2-4; later pages cannot produce the list again (as in real sources).
        val (src, srv) = tocSource(4, "<js>baseUrl.indexOf('toc1')>=0 ? ['BASE/toc2','BASE/toc3','BASE/toc4'] : []</js>")
        try {
            val book = Book("${src.baseUrl}/toc1", "${src.baseUrl}/toc1", src.key, "t", "n", "a")
            val trace = ArrayList<String>()
            val toc = WebBook(src, http).chapters(book, trace)
            assertEquals(20, toc.size)
            assertEquals(listOf("第1章", "第5章", "第10章", "第20章"), listOf(toc[0], toc[4], toc[9], toc[19]).map { it.title })
            assertEquals(4, trace.size)
        } finally { srv.stop(0) }
    }

    @Test fun sourcesWithFarFewerChaptersThanTheOthersAreFlagged() {
        fun r(key: String, chapters: Int, book: String = "斗破苍穹") = ProbeResult(key, key, true, "content", null, 1, 1, "k", book, chapters, 500)
        val results = listOf(r("a", 1660), r("b", 1664), r("c", 1625), r("d", 1914), r("e", 1662), r("tiny", 24), r("other-book", 12, "别的书"))
        assertEquals(setOf("tiny"), TocConsistency.incomplete(results))
        assertEquals(emptySet(), TocConsistency.incomplete(results.take(3)), "too few sources to judge")
    }

    private fun contentSource(nextRule: String): Pair<BookSource, com.sun.net.httpserver.HttpServer> {
        val srv = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        for (p in 1..3) srv.createContext("/c1p$p") { ex ->
            val b = "<html><body><div id='t'>第${p}页的内容。</div></body></html>".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        srv.createContext("/toc") { ex -> val b = "<div id='t'>这是目录页，不是正文。</div>".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        srv.start()
        val base = "http://127.0.0.1:${srv.address.port}"
        val json = """{"bookSourceUrl":"$base","bookSourceName":"t","ruleContent":{"content":"id.t@text","nextContentUrl":${kotlinx.serialization.json.JsonPrimitive(nextRule.replace("BASE", base))}}}"""
        return BookSource.parseList(json).first() to srv
    }

    @Test fun contentRuleThatListsEveryPageGetsAllOfThem() {
        // Only page 1 can produce the list; asking again on page 2 yields nothing, as in real sources.
        val (src, srv) = contentSource("<js>baseUrl.indexOf('c1p1')>=0 ? ['BASE/c1p2','BASE/c1p3'] : []</js>")
        try {
            val b = src.baseUrl
            val book = Book("$b/book", "$b/toc", src.key, "t", "n", "a")
            val text = WebBook(src, http).content(book, Chapter(0, "c", "$b/c1p1"))
            assertEquals("第1页的内容。\n第2页的内容。\n第3页的内容。", text)
        } finally { srv.stop(0) }
    }

    @Test fun lastChaptersNextLinkBackToTheTocIsNotAnotherPage() {
        // The newest chapter has no next chapter to compare with; its "next" link points at the table of contents.
        val (src, srv) = contentSource("<js>'BASE/toc'</js>")
        try {
            val b = src.baseUrl
            val book = Book("$b/book", "$b/toc", src.key, "t", "n", "a")
            val trace = ArrayList<String>()
            val text = WebBook(src, http).content(book, Chapter(0, "c", "$b/c1p1"), nextChapterUrl = null, trace = trace)
            assertEquals("第1页的内容。", text)
            assertEquals(1, trace.size, "must not fetch the table of contents as a page of the chapter")
        } finally { srv.stop(0) }
    }

    @Test fun latestBlockFollowedByAnUnnumberedNoticeStillMovesToTheEnd() {
        // 奇书网 on 剑烛大荒: 94…83 first, then "欢迎收藏", then chapter 1…82.
        val head = (94 downTo 83).map { "第${it}章 新$it" }
        val main = listOf("欢迎收藏") + (1..82).map { "第${it}章 x$it" }
        val fixed = TocFix.apply(chap(*(head + main).toTypedArray()))
        assertEquals("欢迎收藏", fixed.first().title)
        assertEquals("第94章 新94", fixed.last().title)
        assertEquals(95, fixed.size)
        val numbers = fixed.mapNotNull { ChapterNumbers.of(it.title) }
        assertEquals(numbers.sorted(), numbers, "the whole list ends up in ascending order")
    }

    @Test fun aSingleNewestChapterInFrontMovesToo() {
        val fixed = TocFix.apply(chap("第94章 新", "欢迎收藏", *(1..12).map { "第${it}章" }.toTypedArray()))
        assertEquals("第94章 新", fixed.last().title)
    }

    @Test fun aListThatSimplyStartsAtChapterOneIsLeftAlone() {
        val list = chap("欢迎收藏", *(1..30).map { "第${it}章" }.toTypedArray())
        assertEquals(list.map { it.title }, TocFix.apply(list).map { it.title })
    }

    @Test fun theSameChapterPostedTwiceKeepsTheFirst() {
        // 红叶书斋: two different addresses, one title, one text.
        val list = chap(*(1..10).map { "第${it}章 x" }.toTypedArray(), "第10章 x", "第11章 y")
        val fixed = TocFix.apply(list)
        assertEquals(listOf("第9章 x", "第10章 x", "第11章 y"), fixed.takeLast(3).map { it.title })
        assertEquals(11, fixed.size)
    }

    @Test fun freshestSourceComesFirstAndTheSlowOneIsRecognised() {
        fun hit(origin: String, latest: String?) = SearchBook("斗破", "土豆", "u-$origin", origin, origin, latestChapterTitle = latest)
        val slow = hit("slow", "第七十章 夜深")
        val fast = hit("fast", "第九十四章 吊诡")
        val unknown = hit("unknown", null)
        assertEquals(listOf("fast", "slow", "unknown"), Freshness.newestFirst(listOf(unknown, slow, fast)).map { it.origin })
        assertEquals("fast", Freshness.fresherThan(slow, listOf(fast, unknown))?.origin)
        assertEquals(null, Freshness.fresherThan(fast, listOf(slow)), "the newest source has nothing fresher")
        assertEquals(null, Freshness.fresherThan(unknown, listOf(fast)), "no number to compare: do not guess")
    }


    // ---- PreviewGuard: synthetic text (no real novel is stored in the tests) ----------------------------------------------

    private val heroes = listOf("丁松言", "罗十二娘")
    private fun fullChapter(n: Int) = (1..90).joinToString("\n") { i -> "${heroes[i % 2]}在第${n}个夜里走过便宜酒垆的后院，${heroes[(i + 1) % 2]}正在门口等着他，两个人说了第${i}句话。" }

    private val strangers = listOf(
        "乔东阳召开家庭会议的时候，没有一个亲戚愿意站出来替他说话，他笑称自己早就被奶奶开除了族籍。",
        "舰艇甲板上，护航官兵整齐列队，与前来送别的亲人和战友挥手告别，汽笛声久久回荡在港口的上空。",
        "通讯兵联络一三九师师部，却始终无人应答，唯有一个解释，那就是师部已经在炮火中沦陷了。",
        "唐星薇把合同推到叶凌道面前，认真地问他究竟愿不愿意同自己一起开办一家全新的娱乐公司。",
        "池月身前的双座山丘不时撞在他坚硬的脊背上，她心里慌乱得厉害，嘴上却一点也不肯服软。",
        "巴达维亚舰队在混乱之中勉强组织起战列线，但这完全是应急之举，旗舰金狮号已经中弹起火。",
        "瓦岗军的进攻极为顺利，裴仁基的虎牢军团与孟让手底下的士兵都是能征善战的老兵油子。",
        "顾十八娘怔了一怔，打量着眼前这个年轻人，四周听见动静的众人也都突然安静了下来。",
        "收好五行玛瑙的秦逸带着龙宝宝，同众人一起朝着先前那块阴凉的地方慢慢走了过去。",
        "那血族连连闪动，身影在空中留下一道接着一道的残影，每一次出现都在撕扯猎物的衣角。",
        "杨菡突然意识到了将士们的难处，竟然主动提出把自己多年积攒下来的私房钱全都拿出来。",
        "阿玉很是失落，但也知道有些事情不能强求，既然杜若梅不愿意当她的朋友，她也就罢了。",
        "他的生日那天，一家三口去了西班牙的特内里费岛旅行，岛上的海风带着一股咸咸的味道。",
        "波尔多这边的人则无所谓，对于他们而言抽中谁都是一样的，反正最后都是要打一场硬仗。",
    )

    private fun padded() = listOf(
        "丁松言回到偏院，吃饱了肚子，耐心等到午市结束，才直奔便宜酒垆，在后院找到了罗十二娘。",
        "“十二娘，你说那两个人若是被五圣宗的人找到，会不会说出便宜酒垆里有高手？”丁松言低声问道。",
        "罗十二娘上穿空青色圆领短袄，下着松绿色棉布褶裙，比往常少了几分成熟，她只是静静看着丁松言",
    ) + strangers

    private val reference by lazy { PreviewGuard.reference(listOf(fullChapter(1), fullChapter(2), fullChapter(3)))!! }

    @Test fun paddedChapterIsTrimmedToItsRealOpening() {
        val r = PreviewGuard.trim(padded().joinToString("\n"), reference)
        assertTrue(r.trimmed)
        assertEquals(3, r.keptParagraphs)
        assertTrue(r.text.endsWith("静静看着丁松言"))
        assertTrue("乔东阳" !in r.text && "舰艇" !in r.text)
    }

    @Test fun aLongChapterIsNeverTouched() {
        // Even if its second half happens to read like padding, a chapter as long as the free ones is left alone.
        val long = padded().take(3) + (1..9).flatMap { strangers }
        assertEquals(false, PreviewGuard.trim(long.joinToString("\n"), reference).trimmed)
        assertEquals(false, PreviewGuard.trim(fullChapter(9), reference).trimmed)
    }

    @Test fun aShortChapterAboutTheSamePeopleIsNotCut() {
        val short = (1..14).map { "丁松言走进酒垆，罗十二娘迎了出来，两个人默默对坐了很久，谁也没有先开口说第${it}句话。" }
        assertEquals(false, PreviewGuard.trim(short.joinToString("\n"), reference).trimmed)
    }

    @Test fun anOpeningLongerThanTenParagraphsIsNotCut() {
        val opening = (1..12).map { "丁松言与罗十二娘在便宜酒垆的后院里谈了很多事，说到第${it}件的时候天色已经黑了下来。" }
        assertEquals(false, PreviewGuard.trim((opening + strangers).joinToString("\n"), reference).trimmed, "cut would be too deep to be a teaser")
    }

    @Test fun onlyShortChaptersWithManyLongParagraphsAreCandidates() {
        assertTrue(PreviewGuard.isCandidate(padded().joinToString("\n")))
        assertEquals(false, PreviewGuard.isCandidate(fullChapter(1)), "a full-length chapter")
        assertEquals(false, PreviewGuard.isCandidate("第一章\n短短的一章。"), "too little text to judge")
    }

    @Test fun noReferenceMeansNoCut() {
        assertEquals(null, PreviewGuard.reference(listOf("")))
        assertEquals(null, PreviewGuard.reference(listOf("一二三。四五六。")), "too little text for any recurring name")
    }
}
