package kr.ac.campusroute.sharing

import android.content.Context
import kr.ac.campusroute.domain.model.Campus
import org.json.JSONArray
import org.json.JSONObject

/** Only explicitly imported route definitions are stored, never the user's measured positions. */
class SharedRouteStore(context: Context, private val campus: Campus) {
    private val preferences=context.applicationContext.getSharedPreferences("received_routes",Context.MODE_PRIVATE)
    fun read(): List<SharedRoute> = runCatching {
        val array=JSONArray(preferences.getString("routes","[]"))
        (0 until array.length()).mapNotNull { index -> runCatching { decode(array.getJSONObject(index)).also { it.resolve(campus) } }.getOrNull() }
    }.getOrDefault(emptyList())
    fun import(text: String): SharedRoute {
        require(text.toByteArray(Charsets.UTF_8).size<=256*1024) { "경로 파일이 너무 큽니다. 최대 256KB입니다." }
        val route=decode(JSONObject(text)); route.resolve(campus)
        val old=read()
        require(old.none { it.id==route.id }) { "이미 받은 경로입니다. 기존 경로를 삭제한 뒤 다시 가져오세요." }
        require(old.size<100) { "최대 100개까지 보관할 수 있습니다. 사용하지 않는 경로를 삭제하세요." }
        write(listOf(route)+old)
        return route
    }
    fun delete(id: String) { write(read().filterNot { it.id==id }) }
    private fun write(routes: List<SharedRoute>) {
        val array=JSONArray(); routes.forEach { array.put(encode(it)) }
        check(preferences.edit().putString("routes",array.toString()).commit()) { "경로를 저장하지 못했습니다." }
    }
    private fun decode(json: JSONObject): SharedRoute {
        require(json.getInt("schemaVersion")==1) { "지원하지 않는 공유 파일 버전입니다." }
        val ids=json.getJSONArray("nodeIds")
        return SharedRoute(json.getString("id"),json.getString("title"),json.getString("destinationId"),json.getString("mapVersion"),(0 until ids.length()).map { ids.getString(it) })
    }
    private fun encode(route: SharedRoute)=JSONObject().put("schemaVersion",1).put("id",route.id).put("title",route.title).put("destinationId",route.destinationId).put("mapVersion",route.mapVersion).put("nodeIds",JSONArray(route.nodeIds))
}
