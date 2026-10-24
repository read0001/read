# 开卷 OpenRead

兼容 Legado（开源阅读）书源格式的 Android 小说阅读器，Material 3 界面，书源订阅会自动定时换新。

- **书架 / 搜索 / 发现 / 详情 / 阅读**：搜索同时查所有启用的书源，结果按书名合并；发现页按书源的分类浏览。
- **阅读器**：左右翻页和上下滚动两种模式，5 种背景，字号、行距、亮度、保持亮屏；点屏幕左右翻页，点中间呼出菜单；进度精确到字，改字号后位置不丢；预取前后章节，可缓存后面 50 章离线读。
- **换源**：详情页和阅读页都能手动换源；某章读不出来时（开启"自动换源"）自动去别的书源找同一本书，按章节名对齐阅读位置。
- **书源管理**：导入（网址、粘贴 JSON、`legado://import/bookSource?src=…` 链接），单个/全部检测，一键停用检测失败的。
- **定时换源**：见下。

## 定时换源

照 `../tv` 的做法：服务端定时检测，客户端只订阅一个固定地址。

1. `.github/workflows/booksource-check.yml` 每 6 小时在 GitHub Actions 上跑一次 `checker`。
2. `checker` 读取 `booksource-check/candidates.tsv` 里的书源合集，**对每个书源跑完整流程：搜索 → 详情 → 目录 → 一章正文**，四步都通过才算可用（只能连通、能搜索但打不开章节的不算）。
3. 通过的书源写入 `booksource-check/out/booksources.json`（Legado 原格式，Legado 本身也能直接订阅这个文件）。
4. App 订阅该文件：启动时超过 12 小时就更新，另有 WorkManager 每 12 小时后台更新；GitHub 地址同时走多个镜像，谁先返回用谁，GitHub 原站 3 秒内返回则优先。
5. 手动导入的书源不会被订阅更新覆盖；订阅里书源的启用/停用状态在更新时保留。

选源规则（见 `checker/.../Main.kt`）：

- 上次发布的书源这次检测失败一次不会马上删除，连续失败两次才移除，避免一次网络抖动就把好书源换掉。
- 同一个域名只保留一个书源。
- 目录不完整的源会被剔除：同一本书在 5 个以上书源里都通过时，章节数不到中位数一半的（只取到了第一页目录的源）不发布，也不会因为"上次发布过"被保留。
- `booksource-check/blocklist.txt` 里的关键字（成人向站点等）对应的书源不检测、不发布。
- 一个书源都没通过时，`checker` 以退出码 1 结束，不改动已发布的文件。

订阅地址（默认）：

```
https://raw.githubusercontent.com/read0001/read/main/booksource-check/out/booksources.json
```

可以在 App「设置 → 书源订阅」里改成别的目录。

### 本地运行检测

```bash
./gradlew :checker:run --args="$PWD/booksource-check/candidates.tsv $PWD/booksource-check/out --concurrency 16"
./gradlew :checker:run --args="debug <书源 JSON 文件或网址> <书源名称> [关键字]"   # 逐步打印一个书源每一步看到了什么
```

检测器会读环境变量 `HTTPS_PROXY`，加 `--no-proxy` 可忽略。结果取决于运行检测的网络：GitHub Actions 的机房 IP 经常被 Cloudflare 拦截，国内站点在海外网络上可能打不开，所以"可用"是"在检测机器上可用"。

## 目录与正文翻页

遵循 Legado 的约定，并补上常见站点的毛病，保证"最新章"是对的：

- 「下一页」规则返回**多个地址**（常见写法：脚本根据"第 1/83 页"一次生成所有页的地址）时，一次性并发取全，不再逐页链式跟进——链式在第 2 页上规则往往生成不出地址，目录会只剩前两页。正文分页同理。
- 目录规则没写「下一页」时，识别页面里普通的"下一页"链接兜底翻页（只跟同站链接，翻到没有新章节就停）。
- 目录开头粘着倒序的"最新章节"小块（第1593章、1464、1463…，后面才是第1章起的完整列表）时，挪到末尾并转成正序；整份目录是倒序的，整体翻转。标题里没有章节号的目录不动。
- 正文翻页遇到书目录页、书详情页或目录里别的章节地址就停：最新一章没有"下一章"可比较，它的"下一页"链接常常指回目录，之前会把目录页内容拼在正文后面。

## 书评

书详情页的「书评」会显示这本书在**豆瓣读书**上的评分、短评和长评（摘要，点「阅读全文」跳到豆瓣）。

- 按「书名 + 作者」匹配豆瓣条目，匹配不上就明确提示没有，不会显示别的书的评论。豆瓣上的是纸质版，没有出版过的网络小说通常查不到。
- 用的是豆瓣手机站页面本身在用的接口（不需要登录，但不是公开 API），可能随时变化，也可能被限流；只在点开「书评」时才请求，不会批量抓取。不登录时只能看到最热门的几十条，「最新」排序需要登录所以没有。
- **没有章节评论和段评。** 书源格式里有评论规则（`ruleReview`），但我们抓到的 2638 个书源里一个都没写，所以没有数据可用。起点这类站点自己有段评和书评区，但普通请求拿不到：书页只内嵌 3 条精华帖，完整列表要靠页面脚本发请求，而且页面带风控脚本。要做的话需要内嵌 WebView 去渲染页面再读，维护成本高，目前没做。

## 检查更新

和 `../tv` 一样：订阅目录里放一个 `app-version.json`（`booksource-check/out/app-version.json`），App 启动时超过 12 小时检查一次，设置里「检查更新」可以手动查。发现新版本会弹窗（更新内容、大小），可以「立即更新 / 稍后 / 忽略此版本」；下载同样走多个 GitHub 镜像并显示进度，完成后调起系统安装（第一次需要允许“开卷”安装应用）。

镜像不是可信来源，但不用担心被换成别的 App：Android 只会安装与已装版本同一签名密钥的更新。

发布新版本：

```bash
# 1. 改 app/build.gradle.kts 里的 appVersionCode / appVersionName
set -a; . ~/.config/openread/release.env; set +a      # 发布密钥，不在仓库里
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk releases/openread-<versionName>.apk
python3 -I scripts/publish-release.py "更新内容"      # 生成 app-version.json（含大小和 sha256）
git add -A && git commit -m "Release <versionName>" && git push
```

## 项目结构

| 目录 | 内容 |
|---|---|
| `engine/` | 纯 JVM 的书源引擎：Legado 规则解析（jsoup / CSS / XPath / JSONPath / 正则 / `@js` / `{{}}` / `@put` `@get`）、URL 选项、Rhino 脚本沙箱、搜索→详情→目录→正文流程。有离线单元测试。 |
| `checker/` | 书源检测命令行，复用 `engine`。 |
| `app/` | Android 应用（Compose + Material 3 + Room + DataStore + WorkManager + Coil）。 |
| `booksource-check/` | 候选书源合集、屏蔽名单、发布出来的订阅文件。 |

引擎对 JS 做了类白名单沙箱：书源脚本只能用字符串、集合、日期、加解密、jsoup 和引擎自己的桥接对象，碰不到文件、进程、反射和 Android 框架。订阅里的书源来自任何人，这一层不能省。

## 构建

需要 JDK 17+ 和 Android SDK（compileSdk 35）。

```bash
./gradlew :engine:test          # 引擎测试
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

订阅仓库可以用 `-PsubscriptionRepo=owner/repo -PsubscriptionBranch=main` 改。

## 安全说明

和 `../tv` 一样，订阅文件走公共 GitHub 镜像，对镜像返回的内容不做签名校验。书源里的 JS 在引擎的类白名单沙箱里运行（碰不到文件、进程、反射和 Android 框架），但仍然只应该订阅可信的地址。设置里可以改成自己的订阅目录。

## 已知限制

- 需要 WebView 才能打开的书源（强反爬、`java.webView`）暂不支持，检测时会被跳过。
- 只支持文字书源，漫画、音频、文件类书源会被跳过。
- 访问证书链不完整的站点时，对该域名放宽证书校验（浏览器会补全中间证书，Java/Android 不会）；证书正常的站点不受影响。
- 书源里的内容来自第三方网站，使用前请确认版权，本项目不提供任何内容。
