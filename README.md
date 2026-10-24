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
