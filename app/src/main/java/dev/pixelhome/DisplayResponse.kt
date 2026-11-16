package dev.pixelhome

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class DisplayResponse(val data: JSONObject) {
    val id get() = data.getString("response_id")
    val title get() = data.getString("title")
    val summary get() = data.getString("summary")
    val layout get() = data.getString("layout")
    val blocks get() = data.optJSONArray("blocks") ?: JSONArray()
    val template: NativeTemplate? = if(data.optString("protocol_version")=="1.1") TemplateProtocol.parse(data.getJSONObject("template"),layout) else null
}
object DisplayProtocol {
    const val REVISION = "pixel-home-0.4"
    private val layouts = listOf("compact", "reading", "search", "steps", "comparison")
    private fun fields(o: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val keys = o.keys().asSequence().toSet()
        require(keys.containsAll(required) && (keys - required - optional).isEmpty()) { "Unexpected or missing fields" }
    }
    private fun text(o: JSONObject, key: String, max: Int) = (o.get(key) as? String ?: error("Expected text")).also {
        require(it.isNotBlank() && it.codePointCount(0, it.length) <= max)
    }
    private fun list(a: JSONArray, max: Int, length: Int) {
        require(a.length() in 1..max)
        for (i in 0 until a.length()) { val s = a.get(i) as? String ?: error("Expected text"); require(s.isNotBlank() && s.codePointCount(0,s.length) <= length) }
    }
    fun parse(raw: String): DisplayResponse {
        require(raw.toByteArray(Charsets.UTF_8).size <= 32768)
        val o = JSONObject(raw)
        if(o.optString("protocol_version")=="1.1") {
            fields(o,setOf("protocol_version","response_id","layout","title","summary","spoken_text","ttl_seconds","template"))
            require(o.get("layout") in TemplateProtocol.ids)
            require(text(o,"response_id",80).matches(Regex("[a-zA-Z0-9_-]+")))
            text(o,"title",80); text(o,"summary",240); text(o,"spoken_text",400)
            val ttl=o.get("ttl_seconds"); require((ttl is Int || ttl is Long) && (ttl as Number).toLong() in 15..300)
            return DisplayResponse(o)
        }
        fields(o, setOf("protocol_version", "response_id", "layout", "title", "summary", "spoken_text", "blocks", "ttl_seconds"))
        require(o.get("protocol_version") == "1.0" && o.get("layout") in layouts)
        require(text(o, "response_id", 80).matches(Regex("[a-zA-Z0-9_-]+")))
        text(o, "title", 80); text(o, "summary", 240); text(o, "spoken_text", 400)
        val ttl = o.get("ttl_seconds"); require((ttl is Int || ttl is Long) && (ttl as Number).toLong() in 15..300)
        val blocks = o.getJSONArray("blocks"); require(blocks.length() in 1..8)
        for(i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            when(b.getString("type")) {
                "paragraph" -> { fields(b, setOf("type", "text"), setOf("heading")); text(b,"text",4000) }
                "bullets", "steps" -> { fields(b,setOf("type","items"),setOf("heading")); val steps=b.getString("type")=="steps"; list(b.getJSONArray("items"),if(steps)10 else 8,if(steps)600 else 300) }
                "sources" -> {
                    fields(b,setOf("type","items")); val a=b.getJSONArray("items"); require(a.length() in 1..5)
                    for(j in 0 until a.length()) { val s=a.getJSONObject(j); fields(s,setOf("title","url"),setOf("excerpt")); text(s,"title",100)
                        val url=text(s,"url",2048); val u=URI(url); require(u.scheme=="https" && !u.host.isNullOrBlank() && u.rawUserInfo==null && url.none { it.isWhitespace() || it.isISOControl() })
                        if(s.has("excerpt"))text(s,"excerpt",300)
                    }
                }
                "table" -> { fields(b,setOf("type","columns","rows")); val columns=b.getJSONArray("columns"); list(columns,4,60); val rows=b.getJSONArray("rows"); require(rows.length() in 1..8)
                    for(j in 0 until rows.length()) { val row=rows.getJSONArray(j); list(row,4,160); require(row.length()==columns.length()) }
                }
                "status" -> { fields(b,setOf("type","label","state"),setOf("detail")); text(b,"label",120); require(b.get("state") in listOf("success","partial","pending","error")); if(b.has("detail"))text(b,"detail",500) }
                else -> error("Unsupported block")
            }
            if(b.has("heading"))text(b,"heading",80)
        }
        return DisplayResponse(o)
    }
    fun preview() = parse("""{"protocol_version":"1.0","response_id":"preview","layout":"reading","title":"A little more comfort","summary":"Heat pumps move warmth between indoors and outdoors.","spoken_text":"Heat pumps move heat instead of generating it directly.","ttl_seconds":60,"blocks":[{"type":"paragraph","heading":"How it works","text":"In heating mode, refrigerant absorbs heat outdoors and releases it inside. In cooling mode, the cycle reverses."},{"type":"bullets","items":["Use steady temperature settings for consistent comfort.","Keep filters clean and airflow clear.","Check the manufacturer's guidance for your system."]}]}""")
}
