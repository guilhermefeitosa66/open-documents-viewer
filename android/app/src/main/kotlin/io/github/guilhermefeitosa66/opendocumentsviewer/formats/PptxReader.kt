package io.github.guilhermefeitosa66.opendocumentsviewer.formats

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * PowerPoint 2007+ (.pptx, .ppsx, .potx, .pptm) into the presentation model, with no library.
 * Placeholders without their own position take it from the slide layout, then the master; theme
 * colors go through the master's color map; text sizes default to the master's text styles. Groups
 * are flattened with their transform. Macros are never read. Ported from the Linux app's reader.
 */
object PptxReader {
    class NotPptx(message: String) : Exception(message)

    private val PRESETS = mapOf(
        "rect" to "rect", "roundRect" to "roundRect", "ellipse" to "ellipse", "line" to "line",
        "straightConnector1" to "line", "snip1Rect" to "rect", "flowChartProcess" to "rect",
        "flowChartAlternateProcess" to "roundRect", "flowChartConnector" to "ellipse",
    )

    fun read(bytes: ByteArray): Presentation {
        if (!ZipFiles.isZip(bytes)) throw NotPptx("not a zip file (an encrypted or older PowerPoint file?)")
        val files = ZipFiles.entries(bytes) { it.endsWith(".xml") || it.endsWith(".rels") }
        return Pkg(files).build()
    }

    private class Pkg(val files: Map<String, ByteArray>) {
        val theme = HashMap<String, Int>()
        val clrMap = hashMapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
        val cache = HashMap<String, org.jsoup.nodes.Document?>()

        fun xml(name: String?): org.jsoup.nodes.Document? {
            name ?: return null
            return cache.getOrPut(name) { files[name]?.let { Jsoup.parse(String(it, Charsets.UTF_8), "", Parser.xmlParser()) } }
        }

        /** Relationship id → (target, type's last segment). */
        fun rels(part: String?): Map<String, Pair<String, String>> {
            part ?: return emptyMap()
            val base = part.substringBeforeLast('/', "")
            val path = (if (base.isEmpty()) "" else "$base/") + "_rels/" + part.substringAfterLast('/') + ".rels"
            val x = xml(path) ?: return emptyMap()
            return x.getElementsByTag("Relationship").associate { r ->
                val t = r.attr("Target")
                val target = if (r.attr("TargetMode") == "External") t else if (t.startsWith("/")) t.trimStart('/') else ZipFiles.resolve(base, t)
                r.attr("Id") to (target to r.attr("Type").substringAfterLast('/'))
            }
        }

        fun loadTheme(master: String) {
            for ((target, kind) in rels(master).values) {
                if (kind != "theme") continue
                val root = xml(target) ?: continue
                val scheme = root.getElementsByTag("a:clrScheme").firstOrNull() ?: continue
                for (child in scheme.children()) {
                    val name = child.tagName().substringAfter(':')
                    val srgb = child.children().firstOrNull { it.tagName() == "a:srgbClr" }
                    val sys = child.children().firstOrNull { it.tagName() == "a:sysClr" }
                    val hex = srgb?.attr("val") ?: sys?.attr("lastClr")?.ifEmpty { "000000" }
                    hex?.toIntOrNull(16)?.let { theme[name] = 0xFF000000.toInt() or it }
                }
            }
            xml(master)?.getElementsByTag("p:clrMap")?.firstOrNull()?.attributes()?.forEach { clrMap[it.key] = it.value }
        }

        /** A color from a fill-like element (solidFill, fgClr, a style reference…). */
        fun color(parent: Element?): Int {
            parent ?: return 0
            for (el in parent.children()) {
                val base: Int? = when (el.tagName()) {
                    "a:srgbClr" -> el.attr("val").toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
                    "a:schemeClr" -> theme[clrMap[el.attr("val")] ?: el.attr("val")]
                    "a:sysClr" -> el.attr("lastClr").ifEmpty { "000000" }.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
                    "a:prstClr" -> when (el.attr("val")) {
                        "white" -> 0xFFFFFFFF.toInt(); "red" -> 0xFFFF0000.toInt(); "blue" -> 0xFF0000FF.toInt()
                        "green" -> 0xFF008000.toInt(); "yellow" -> 0xFFFFFF00.toInt(); "gray" -> 0xFF808080.toInt()
                        else -> 0xFF000000.toInt()
                    }
                    else -> null
                }
                if (base != null) {
                    fun mod(n: String) = el.children().firstOrNull { it.tagName() == "a:$n" }?.attr("val")?.toIntOrNull()?.let { it / 100000f }
                    return lumMod(base, mod("lumMod"), mod("lumOff"), mod("tint"), mod("shade"))
                }
            }
            return 0
        }

        /** (color, said): `said` is true when the element decides the fill (noFill included). */
        fun fill(spPr: Element?): Pair<Int, Boolean> {
            spPr ?: return 0 to false
            for (c in spPr.children()) when (c.tagName()) {
                "a:noFill" -> return 0 to true
                "a:solidFill" -> return color(c) to true
                "a:gradFill" -> return color(c.getElementsByTag("a:gs").firstOrNull()) to true
            }
            return 0 to false
        }

        fun phOf(sp: Element): Pair<String, String?>? {
            val nv = sp.children().firstOrNull { it.tagName().startsWith("p:nv") } ?: return null
            val ph = nv.children().firstOrNull { it.tagName() == "p:nvPr" }?.children()?.firstOrNull { it.tagName() == "p:ph" } ?: return null
            return ph.attr("type").ifEmpty { "body" } to ph.attr("idx").ifEmpty { null }
        }

        fun findPh(part: String?, ph: Pair<String, String?>): Element? {
            val root = xml(part) ?: return null
            var exact: Element? = null
            var alias: Element? = null
            for (sp in root.getElementsByTag("p:sp")) {
                val other = phOf(sp) ?: continue
                if (ph.second != null && other.second == ph.second) return sp
                if (other.first == ph.first) { if (exact == null) exact = sp }
                else if (ph.first in setOf("ctrTitle", "title") && other.first in setOf("ctrTitle", "title")) { if (alias == null) alias = sp }
            }
            return exact ?: alias
        }

        fun build(): Presentation {
            val root = xml("ppt/presentation.xml") ?: throw NotPptx("ppt/presentation.xml is missing or damaged")
            val sz = root.getElementsByTag("p:sldSz").firstOrNull()
            val width = sz?.attr("cx")?.toLongOrNull() ?: 9144000
            val height = sz?.attr("cy")?.toLongOrNull() ?: 6858000
            val rels = rels("ppt/presentation.xml")
            var themeLoaded = false
            val slides = ArrayList<Slide>()
            for (sid in root.getElementsByTag("p:sldId")) {
                val part = rels[sid.attr("r:id")]?.first ?: continue
                val sroot = xml(part) ?: continue
                val srels = rels(part)
                val layout = srels.values.firstOrNull { it.second == "slideLayout" }?.first
                val master = rels(layout).values.firstOrNull { it.second == "slideMaster" }?.first
                if (master != null && !themeLoaded) { loadTheme(master); themeLoaded = true }
                val reader = SlideReader(this, part, layout, master, srels)
                val tree = sroot.getElementsByTag("p:spTree").firstOrNull()
                val shapes = tree?.let { reader.shapes(it, null) }.orEmpty()
                val (bg, bgImage) = reader.background()
                val title = tree?.children()?.firstOrNull { phOf(it)?.first in setOf("title", "ctrTitle") }
                    ?.getElementsByTag("a:t")?.joinToString("") { it.text() }?.split(Regex("\\s+"))?.joinToString(" ")?.trim().orEmpty()
                val notesPart = srels.values.firstOrNull { it.second == "notesSlide" }?.first
                val notes = xml(notesPart)?.getElementsByTag("p:sp")?.firstOrNull { phOf(it)?.first == "body" }
                    ?.getElementsByTag("a:p")?.joinToString("\n") { p -> p.getElementsByTag("a:t").joinToString("") { it.text() } }?.trim().orEmpty()
                slides += Slide(shapes, bg, bgImage, notes, title, sroot.children().firstOrNull()?.attr("show") in setOf("0", "false"))
            }
            if (slides.isEmpty()) throw NotPptx("no slides")
            return Presentation(width, height, slides)
        }
    }

    private class SlideReader(val pkg: Pkg, val part: String, val layout: String?, val master: String?, val rels: Map<String, Pair<String, String>>) {
        fun defaultSize(ph: Pair<String, String?>?, level: Int): Float {
            val kind = ph?.first ?: "other"
            val styleName = if (kind in setOf("title", "ctrTitle")) "p:titleStyle" else if (ph != null) "p:bodyStyle" else "p:otherStyle"
            pkg.xml(master)?.getElementsByTag(styleName)?.firstOrNull()?.children()?.firstOrNull { it.tagName() == "a:lvl${level + 1}pPr" }
                ?.children()?.firstOrNull { it.tagName() == "a:defRPr" }?.attr("sz")?.toFloatOrNull()?.let { return it / 100 }
            return (when (kind) { "title", "ctrTitle" -> 44f; "subTitle" -> 32f; else -> if (ph != null) 28f else 18f }) - (if (ph != null) 4f * level else 0f)
        }

        private fun xfrm(el: Element?): LongArray? {
            val x = el?.children()?.firstOrNull { it.tagName() == "a:xfrm" || it.tagName() == "p:xfrm" } ?: return null
            val off = x.children().firstOrNull { it.tagName() == "a:off" } ?: return null
            val ext = x.children().firstOrNull { it.tagName() == "a:ext" } ?: return null
            return longArrayOf(
                off.attr("x").toLongOrNull() ?: 0, off.attr("y").toLongOrNull() ?: 0,
                ext.attr("cx").toLongOrNull() ?: 0, ext.attr("cy").toLongOrNull() ?: 0,
                (x.attr("rot").toLongOrNull() ?: 0),
            )
        }

        /** Transform: (offset x, offset y, scale x, scale y). */
        fun shapes(tree: Element, t: DoubleArray?): List<SlideShape> {
            val out = ArrayList<SlideShape>()
            for (el in tree.children()) {
                when (el.tagName()) {
                    "p:grpSp" -> {
                        val x = el.children().firstOrNull { it.tagName() == "p:grpSpPr" }?.children()?.firstOrNull { it.tagName() == "a:xfrm" }
                        fun ch(n: String) = x?.children()?.firstOrNull { it.tagName() == n }
                        val off = ch("a:off"); val ext = ch("a:ext"); val chOff = ch("a:chOff"); val chExt = ch("a:chExt")
                        if (off != null && ext != null && chOff != null && chExt != null) {
                            val sx = (ext.attr("cx").toDoubleOrNull() ?: 1.0) / maxOf(1.0, chExt.attr("cx").toDoubleOrNull() ?: 1.0)
                            val sy = (ext.attr("cy").toDoubleOrNull() ?: 1.0) / maxOf(1.0, chExt.attr("cy").toDoubleOrNull() ?: 1.0)
                            val inner = doubleArrayOf(
                                (off.attr("x").toDoubleOrNull() ?: 0.0) - (chOff.attr("x").toDoubleOrNull() ?: 0.0) * sx,
                                (off.attr("y").toDoubleOrNull() ?: 0.0) - (chOff.attr("y").toDoubleOrNull() ?: 0.0) * sy, sx, sy,
                            )
                            out += shapes(el, compose(t, inner))
                        } else out += shapes(el, t)
                    }
                    "p:sp", "p:cxnSp", "p:pic", "p:graphicFrame" -> shape(el)?.let { s ->
                        if (t != null) {
                            s.x = (t[0] + s.x * t[2]).toLong(); s.y = (t[1] + s.y * t[3]).toLong()
                            s.w = (s.w * t[2]).toLong(); s.h = (s.h * t[3]).toLong()
                        }
                        out += s
                    }
                    "mc:AlternateContent" -> el.children().firstOrNull { it.tagName() == "mc:Fallback" }?.let { out += shapes(it, t) }
                }
            }
            return out
        }

        private fun compose(outer: DoubleArray?, inner: DoubleArray): DoubleArray {
            outer ?: return inner
            return doubleArrayOf(outer[0] + inner[0] * outer[2], outer[1] + inner[1] * outer[3], outer[2] * inner[2], outer[3] * inner[3])
        }

        fun shape(el: Element): SlideShape? {
            val ph = pkg.phOf(el)
            val spPr = el.children().firstOrNull { it.tagName() == "p:spPr" }
            var geom = if (el.tagName() == "p:graphicFrame") xfrm(el) else xfrm(spPr)
            val inherited = ArrayList<Element>()
            if (ph != null) {
                var found = listOfNotNull(layout, master).mapNotNull { pkg.findPh(it, ph) }
                val exact = found.filter { pkg.phOf(it)?.first == ph.first || (ph.second != null && pkg.phOf(it)?.second == ph.second) }
                if (exact.isNotEmpty()) found = exact
                for (sp in found) {
                    inherited.add(sp)
                    if (geom == null) geom = xfrm(sp.children().firstOrNull { it.tagName() == "p:spPr" })
                }
            }
            val g = geom ?: return null
            val rot = g[4] / 60000f
            when (el.tagName()) {
                "p:pic" -> {
                    val rid = el.getElementsByTag("a:blip").firstOrNull()?.attr("r:embed")
                    val target = rid?.let { rels[it]?.first }
                    return SlideShape("image", g[0], g[1], g[2], g[3], image = target, rotation = rot)
                }
                "p:graphicFrame" -> {
                    val tbl = el.getElementsByTag("a:tbl").firstOrNull() ?: return null
                    val cols = tbl.getElementsByTag("a:gridCol").map { it.attr("w").toLongOrNull() ?: 0 }
                    val rows = tbl.getElementsByTag("a:tr").map { tr ->
                        tr.children().filter { it.tagName() == "a:tc" }.map { tc -> paragraphs(tc.children().firstOrNull { it.tagName() == "a:txBody" }, null, emptyList()) }
                    }
                    return SlideShape("table", g[0], g[1], g[2], g[3], rows = rows, colWidths = cols, line = 0xFF808080.toInt())
                }
            }
            val prst = spPr?.children()?.firstOrNull { it.tagName() == "a:prstGeom" }?.attr("prst")
            val kind = if (el.tagName() == "p:cxnSp") "line" else PRESETS[prst] ?: "rect"
            val style = el.children().firstOrNull { it.tagName() == "p:style" }
            var (fill, said) = pkg.fill(spPr)
            if (!said) style?.children()?.firstOrNull { it.tagName() == "a:fillRef" }?.takeIf { it.attr("idx").let { i -> i.isNotEmpty() && i != "0" } }?.let { fill = pkg.color(it) }
            val ln = spPr?.children()?.firstOrNull { it.tagName() == "a:ln" }
            var line = 0
            var width = 1f
            val lnNoFill = ln?.children()?.any { it.tagName() == "a:noFill" } == true
            if (ln != null && !lnNoFill) {
                line = pkg.color(ln.children().firstOrNull { it.tagName() == "a:solidFill" })
                width = (ln.attr("w").toFloatOrNull() ?: 12700f) / 12700f
            }
            if (line == 0 && !lnNoFill) {
                style?.children()?.firstOrNull { it.tagName() == "a:lnRef" }?.takeIf { it.attr("idx").let { i -> i.isNotEmpty() && i != "0" } }?.let { line = pkg.color(it) }
            }
            // A picture fill on a shape.
            val blipFill = spPr?.children()?.firstOrNull { it.tagName() == "a:blipFill" }?.getElementsByTag("a:blip")?.firstOrNull()?.attr("r:embed")?.let { rels[it]?.first }
            val body = el.children().firstOrNull { it.tagName() == "p:txBody" }
            var paragraphs = emptyList<SlideParagraph>()
            var anchor = 't'
            var insets = longArrayOf(91440, 45720, 91440, 45720)
            if (body != null) {
                paragraphs = paragraphs(body, ph, inherited)
                val bpr = body.children().firstOrNull { it.tagName() == "a:bodyPr" }
                var a = bpr?.attr("anchor")?.ifEmpty { null }
                if (a == null) for (sp in inherited) {
                    sp.getElementsByTag("a:bodyPr").firstOrNull()?.attr("anchor")?.ifEmpty { null }?.let { a = it }
                    if (a != null) break
                }
                anchor = when (a) { "ctr" -> 'm'; "b" -> 'b'; else -> 't' }
                if (bpr != null) insets = longArrayOf(
                    bpr.attr("lIns").toLongOrNull() ?: 91440, bpr.attr("tIns").toLongOrNull() ?: 45720,
                    bpr.attr("rIns").toLongOrNull() ?: 91440, bpr.attr("bIns").toLongOrNull() ?: 45720,
                )
            }
            return SlideShape(
                if (blipFill != null) "image" else kind, g[0], g[1], g[2], g[3], fill, line, width,
                image = blipFill, paragraphs = paragraphs, anchor = anchor, rotation = rot, insets = insets,
            )
        }

        fun paragraphs(body: Element?, ph: Pair<String, String?>?, inherited: List<Element>): List<SlideParagraph> {
            body ?: return emptyList()
            val out = ArrayList<SlideParagraph>()
            val counters = HashMap<Int, Int>()
            for (p in body.children().filter { it.tagName() == "a:p" }) {
                val ppr = p.children().firstOrNull { it.tagName() == "a:pPr" }
                val level = ppr?.attr("lvl")?.toIntOrNull() ?: 0
                var align = ppr?.attr("algn")?.ifEmpty { null }
                if (align == null) for (sp in inherited) {
                    sp.getElementsByTag("a:lvl${level + 1}pPr").firstOrNull()?.attr("algn")?.ifEmpty { null }?.let { align = it }
                    if (align != null) break
                }
                var size = defaultSize(ph, level)
                for (sp in inherited) {
                    val d = sp.getElementsByTag("a:lvl${level + 1}pPr").firstOrNull()?.children()?.firstOrNull { it.tagName() == "a:defRPr" }?.attr("sz")?.toFloatOrNull()
                    if (d != null) { size = d / 100; break }
                }
                var bullet: String? = null
                ppr?.children()?.firstOrNull { it.tagName() == "a:buChar" }?.let { bullet = it.attr("char").ifEmpty { "•" } }
                if (bullet == null && ppr?.children()?.any { it.tagName() == "a:buAutoNum" } == true) {
                    counters[level] = (counters[level] ?: 0) + 1
                    bullet = "${counters[level]}."
                }
                if (bullet == null && ph?.first == "body" && ppr?.children()?.none { it.tagName() == "a:buNone" } != false) bullet = "•"
                val runs = ArrayList<SlideRun>()
                for (r in p.children()) {
                    when (r.tagName()) {
                        "a:br" -> runs += SlideRun("\n", size = size)
                        "a:r", "a:fld" -> {
                            val rpr = r.children().firstOrNull { it.tagName() == "a:rPr" }
                            val t = r.children().firstOrNull { it.tagName() == "a:t" }?.wholeText().orEmpty()
                            val face = rpr?.children()?.firstOrNull { it.tagName() == "a:latin" }?.attr("typeface").orEmpty().lowercase()
                            runs += SlideRun(
                                t,
                                bold = rpr?.attr("b") in setOf("1", "true"),
                                italic = rpr?.attr("i") in setOf("1", "true"),
                                underline = rpr?.attr("u")?.let { it.isNotEmpty() && it != "none" } == true,
                                strike = rpr?.attr("strike")?.let { it.isNotEmpty() && it != "noStrike" } == true,
                                size = rpr?.attr("sz")?.toFloatOrNull()?.let { it / 100 } ?: size,
                                color = pkg.color(rpr?.children()?.firstOrNull { it.tagName() == "a:solidFill" }),
                                mono = listOf("courier", "consolas", "mono").any { it in face },
                            )
                        }
                    }
                }
                if (runs.isEmpty() || runs.all { it.text.isEmpty() }) { out += SlideParagraph(listOf(SlideRun("", size = size)), 'l', level, null); continue }
                out += SlideParagraph(runs, when (align) { "ctr" -> 'c'; "r" -> 'r'; "just" -> 'j'; else -> 'l' }, level, bullet)
            }
            return out
        }

        /** (color, picture path) of the slide, its layout or master, else the theme's background. */
        fun background(): Pair<Int, String?> {
            for (p in listOfNotNull(part, layout, master)) {
                val bg = pkg.xml(p)?.getElementsByTag("p:bg")?.firstOrNull() ?: continue
                val bgPr = bg.children().firstOrNull { it.tagName() == "p:bgPr" }
                if (bgPr != null) {
                    bgPr.getElementsByTag("a:blip").firstOrNull()?.attr("r:embed")?.let { rid ->
                        return 0xFFFFFFFF.toInt() to pkg.rels(p)[rid]?.first
                    }
                    val (fill, said) = pkg.fill(bgPr)
                    if (said) return (fill.takeIf { it != 0 } ?: 0xFFFFFFFF.toInt()) to null
                }
                bg.children().firstOrNull { it.tagName() == "p:bgRef" }?.let { ref -> pkg.color(ref).takeIf { it != 0 }?.let { return it to null } }
            }
            return (pkg.theme[pkg.clrMap["bg1"] ?: "lt1"] ?: 0xFFFFFFFF.toInt()) to null
        }
    }

    /** Luminance modulation and tint/shade, like DrawingML. */
    internal fun lumMod(argb: Int, mod: Float?, off: Float?, tint: Float?, shade: Float?): Int {
        if (mod == null && off == null && tint == null && shade == null) return argb
        var r = ((argb shr 16) and 0xFF) / 255f
        var g = ((argb shr 8) and 0xFF) / 255f
        var b = (argb and 0xFF) / 255f
        if (mod != null || off != null) {
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            var l = (max + min) / 2
            val d = max - min
            var h = 0f; var s = 0f
            if (d > 0) {
                s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
                h = when (max) { r -> ((g - b) / d + (if (g < b) 6 else 0)); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6
            }
            if (mod != null) l *= mod
            if (off != null) l += off
            l = l.coerceIn(0f, 1f)
            fun hue(p: Float, q: Float, t0: Float): Float {
                var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
                return when { t < 1f / 6 -> p + (q - p) * 6 * t; t < 0.5f -> q; t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6; else -> p }
            }
            if (s == 0f) { r = l; g = l; b = l } else {
                val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
                val p = 2 * l - q
                r = hue(p, q, h + 1f / 3); g = hue(p, q, h); b = hue(p, q, h - 1f / 3)
            }
        }
        if (tint != null) { r += (1 - r) * (1 - tint); g += (1 - g) * (1 - tint); b += (1 - b) * (1 - tint) }
        if (shade != null) { r *= shade; g *= shade; b *= shade }
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
        return (argb and 0xFF000000.toInt()) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
