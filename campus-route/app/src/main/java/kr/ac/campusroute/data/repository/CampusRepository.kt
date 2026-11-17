package kr.ac.campusroute.data.repository

import android.content.res.AssetManager
import kr.ac.campusroute.domain.model.*
import org.json.JSONObject

interface CampusRepository { fun load(): Campus }

class AssetCampusRepository(private val assets: AssetManager) : CampusRepository {
    private fun points(array: org.json.JSONArray): List<Point> = (0 until array.length()).map { val p = array.getJSONObject(it); Point(p.getDouble("x"), p.getDouble("y")) }
    override fun load(): Campus {
        val json = JSONObject(assets.open("gaesin_campus.json").bufferedReader().use { it.readText() })
        val nodesJson = json.getJSONArray("nodes")
        val nodes = (0 until nodesJson.length()).map { i ->
            val n = nodesJson.getJSONObject(i)
            Node(n.getString("id"), Point(n.getDouble("x"), n.getDouble("y")), n.getString("name"), n.getDouble("latitude"), n.getDouble("longitude"))
        }
        val edgesJson = json.getJSONArray("edges")
        val byId = nodes.associateBy { it.id }
        val edges = (0 until edgesJson.length()).map { i ->
            val e = edgesJson.getJSONObject(i)
            val from = e.getString("from"); val to = e.getString("to")
            Edge(from, to, e.optDouble("distance", byId.getValue(from).position.distance(byId.getValue(to).position)), e.optBoolean("accessible", true), e.optBoolean("directed", false))
        }
        val buildingsJson = json.getJSONArray("buildings")
        val buildings = (0 until buildingsJson.length()).map { i ->
            val b = buildingsJson.getJSONObject(i)
            Building(b.getString("name"), Point(b.getDouble("x"), b.getDouble("y")), b.getDouble("width"), b.getDouble("depth"), b.getDouble("height"), points(b.getJSONArray("footprint")))
        }
        val destinationsJson = json.getJSONArray("destinations")
        val destinations = (0 until destinationsJson.length()).map {
            val d = destinationsJson.getJSONObject(it)
            Destination(d.getString("id"), d.getString("name"), d.getString("nodeId"))
        }
        return Campus(json.getString("version"), json.getString("name"), nodes, edges, buildings, points(json.getJSONArray("boundary")), json.getString("startNodeId"), destinations)
    }
}
