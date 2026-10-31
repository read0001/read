package app.openread.ui.reader

/**
 * 音量键翻页：阅读页打开、并且开着“音量键翻页”时，[handler] 不为空，MainActivity 把音量键交给它。
 * 返回 true 表示已经处理（不再调音量）。
 */
object ReaderKeys {
    @Volatile var handler: ((next: Boolean) -> Boolean)? = null
}

/** 阅读页把翻页动作登记在这里，音量键和自动翻页都通过它翻页。 */
class PageTurner {
    var next: (() -> Unit)? = null
    var prev: (() -> Unit)? = null
}
