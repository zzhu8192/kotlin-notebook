package kotnb.display

/**
 * A cell result rendered as HTML instead of text. Cells can use it without imports, e.g. `html("<b>bold</b>")`.
 */
class Html(val html: String) {
    override fun toString() = html
}

fun html(html: String) = Html(html)
