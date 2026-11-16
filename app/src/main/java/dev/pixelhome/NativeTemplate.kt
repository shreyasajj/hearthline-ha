package dev.pixelhome

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

sealed interface TemplateData
 data class JourneyData(val destination:String, val locality:String, val durationSeconds:Double, val distanceMeters:Double,
    val departure:Instant, val timezone:ZoneId, val traffic:String, val directions:List<String>, val geometry:List<Pair<Double,Double>>, val attribution:String):TemplateData
 data class WeatherHour(val at:Instant,val temperature:Double?,val probability:Double?,val condition:String)
 data class WeatherData(val location:String,val condition:String,val temperature:Double?,val unit:String,val high:Double?,val low:Double?,val probability:Double?,val hours:List<WeatherHour>):TemplateData
 data class HomeItem(val entityId:String,val name:String,val state:String,val unit:String?)
 data class HomeData(val scope:String,val items:List<HomeItem>):TemplateData
 data class NativeTemplate(val state:String,val source:String,val updatedAt:Instant,val staleAfter:Double,val result:TemplateData?) {
    fun stale(now:Instant=Instant.now()) = now.isAfter(updatedAt.plusMillis((staleAfter*1000).toLong()))
 }
object TemplateProtocol {
    val ids=setOf("journey","weather","home_status")
    private fun fields(o:JSONObject,vararg names:String) { require(o.keys().asSequence().toSet()==names.toSet()) { "Unexpected template fields" } }
    private fun string(o:JSONObject,k:String,max:Int):String = (o.get(k) as? String ?: error("Expected text")).also { require(it.isNotBlank() && it.codePointCount(0,it.length)<=max) }
    private fun number(o:JSONObject,k:String,min:Double,max:Double,nullable:Boolean=false):Double? {
        if(nullable && o.isNull(k)) { require(o.has(k)); return null }
        return numeric(o.get(k),min,max)
    }
    private fun numeric(v:Any,min:Double,max:Double):Double = (v as? Number ?: error("Expected number")).toDouble().also { require(it.isFinite() && it in min..max) }
    private fun time(o:JSONObject,k:String)=OffsetDateTime.parse(string(o,k,40)).toInstant()
    private fun array(o:JSONObject,k:String,max:Int)=o.getJSONArray(k).also { require(it.length()<=max) }
    private fun <T> JSONArray.mapItems(f:(Int)->T)=(0 until length()).map(f)
    fun parse(o:JSONObject,kind:String):NativeTemplate {
        require(kind in ids)
        fields(o,"schema_version","state","source","updated_at","stale_after_seconds","data")
        require(o.get("schema_version") is Int && o.getInt("schema_version")==1)
        val state=string(o,"state",20); require(state in setOf("loading","ready","partial","unavailable","error","clarification"))
        val source=string(o,"source",120); val updated=time(o,"updated_at"); val stale=number(o,"stale_after_seconds",1.0,86400.0)!!
        if(o.isNull("data")) { require(state !in setOf("ready","partial")); return NativeTemplate(state,source,updated,stale,null) }
        require(state in setOf("ready","partial")); val d=o.getJSONObject("data")
        val result:TemplateData=when(kind) {
            "journey" -> {
                fields(d,"destination","locality","duration_seconds","distance_meters","departure_at","timezone","traffic","directions","geometry","attribution")
                val traffic=string(d,"traffic",20); require(traffic in setOf("live","unavailable"))
                val directions=array(d,"directions",20).let { a->a.mapItems { i-> (a.get(i) as? String ?: error("Expected direction")).also { require(it.isNotBlank() && it.codePointCount(0,it.length)<=300) } } }
                val geometry=array(d,"geometry",256).let { a->require(a.length()!=1); a.mapItems { i->val p=a.getJSONArray(i); require(p.length()==2); numeric(p.get(0),-180.0,180.0) to numeric(p.get(1),-85.0,85.0) } }
                JourneyData(string(d,"destination",120),string(d,"locality",240),number(d,"duration_seconds",0.0,604800.0)!!,number(d,"distance_meters",0.0,20000000.0)!!,
                    time(d,"departure_at"),ZoneId.of(string(d,"timezone",80)),traffic,directions,geometry,string(d,"attribution",240))
            }
            "weather" -> {
                fields(d,"location","condition","temperature","unit","high","low","precipitation_probability","hours")
                val unit=string(d,"unit",2); require(unit in setOf("°C","°F","K"))
                val hours=array(d,"hours",6).let { a->a.mapItems { i->val h=a.getJSONObject(i); fields(h,"at","temperature","precipitation_probability","condition"); WeatherHour(time(h,"at"),number(h,"temperature",-273.15,1000.0,true),number(h,"precipitation_probability",0.0,100.0,true),string(h,"condition",80)) } }
                WeatherData(string(d,"location",120),string(d,"condition",80),number(d,"temperature",-273.15,1000.0,true),unit,number(d,"high",-273.15,1000.0,true),number(d,"low",-273.15,1000.0,true),number(d,"precipitation_probability",0.0,100.0,true),hours)
            }
            else -> {
                fields(d,"scope","items")
                val items=array(d,"items",16).let { a->a.mapItems { i->val h=a.getJSONObject(i); fields(h,"entity_id","name","state","unit"); HomeItem(string(h,"entity_id",120).also { require(it.matches(Regex("[a-z_]+\\.[a-z0-9_]+"))) },string(h,"name",120),string(h,"state",160),if(h.isNull("unit")) null else string(h,"unit",20)) } }
                require(items.map { it.entityId }.distinct().size==items.size)
                HomeData(string(d,"scope",160),items)
            }
        }
        return NativeTemplate(state,source,updated,stale,result)
    }
}
