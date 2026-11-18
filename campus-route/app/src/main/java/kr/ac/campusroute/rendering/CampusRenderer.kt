package kr.ac.campusroute.rendering

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kr.ac.campusroute.domain.model.*
import kr.ac.campusroute.routing.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

data class OverviewCamera(val center: Point, val zoom: Float)

/** Real world-space meshes, perspective projection and depth testing; no Canvas projection. */
class CampusRenderer(private val campus: Campus, private val route: Route) : GLSurfaceView.Renderer {
    @Volatile var showUserMarker = true
    @Volatile var userHeadingKnown = true
    @Volatile var showDestination = true
    @Volatile var userMarkerReliable = true
    @Volatile var userAccuracyMetres: Double? = null
    private var previouslyVisible = false
    @Volatile var overview: OverviewCamera? = null
    private var aspect = 1f
    @Volatile var target: RoutePose = routePose(route, 0.0)
    private var cameraPosition = target.position
    private var cameraHeading = target.heading
    private var avatarPosition = target.position
    private var avatarHeading = target.heading
    private var previousTime = 0L
    private var program = 0
    private var positionAttribute = 0
    private var shadeAttribute = 0
    private var matrixUniform = 0
    private var colorUniform = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val cube = makeCube()
    private val avatarSphere = makeAvatarSphere()
    private val buildingMesh = makeBuildingMesh()
    private val pathMesh = makePathMesh()
    private val shadowMesh = makeShadowMesh()
    private val accuracyRing = buffer((0 until 96).flatMap { i ->
        val a=i*2*PI/96; val b=(i+1)*2*PI/96
        listOf(cos(a).toFloat(),0f,sin(a).toFloat(),1f,cos(b).toFloat(),0f,sin(b).toFloat(),1f)
    }.toFloatArray())
    private val arrow = buffer(floatArrayOf(
        -.24f,0f,.25f,1f, .24f,0f,.25f,1f, .24f,0f,-3.5f,1f,
        -.24f,0f,.25f,1f, .24f,0f,-3.5f,1f, -.24f,0f,-3.5f,1f,
        0f,0f,-5.5f,1f, -1.05f,0f,-3.2f,1f, 1.05f,0f,-3.2f,1f))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glClearColor(.96f, .96f, .96f, 1f)
        glEnable(GL_DEPTH_TEST)
        val vertex = shader(GL_VERTEX_SHADER, "attribute vec3 aPosition; attribute float aShade; uniform mat4 uMvp; varying float vShade; void main(){gl_Position=uMvp*vec4(aPosition,1.0);vShade=aShade;}")
        val fragment = shader(GL_FRAGMENT_SHADER, "precision mediump float; uniform vec4 uColor; varying float vShade; void main(){gl_FragColor=vec4(uColor.rgb*vShade,uColor.a);}")
        program = glCreateProgram()
        glAttachShader(program, vertex); glAttachShader(program, fragment); glLinkProgram(program)
        val status = IntArray(1); glGetProgramiv(program, GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { glGetProgramInfoLog(program) }
        glDeleteShader(vertex); glDeleteShader(fragment)
        positionAttribute = glGetAttribLocation(program, "aPosition")
        shadeAttribute = glGetAttribLocation(program, "aShade")
        matrixUniform = glGetUniformLocation(program, "uMvp")
        colorUniform = glGetUniformLocation(program, "uColor")
        previousTime = 0
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 53f, aspect, 0.2f, 6000f)
    }
    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (previousTime == 0L) 0.016 else ((now - previousTime) / 1e9).coerceIn(0.0, 0.1)
        previousTime = now
        val pose = target // One immutable snapshot shared from the Compose/UI thread.
        // Coordinates are the accepted measurement, not an independently animated simulation.
        avatarPosition = pose.position
        cameraPosition = pose.position
        if (!previouslyVisible || !userMarkerReliable) {
            avatarHeading=pose.heading; cameraHeading=pose.heading
        } else {
            avatarHeading = smoothHeading(avatarHeading, pose.heading, dt)
            cameraHeading = smoothHeading(cameraHeading, pose.heading, dt)
        }
        previouslyVisible = showUserMarker
        val dx = sin(cameraHeading).toFloat(); val dz = -cos(cameraHeading).toFloat()
        val x = cameraPosition.x.toFloat(); val z = -cameraPosition.y.toFloat()
        Matrix.setLookAtM(view, 0, x - dx * 19, 19f, z - dz * 19, x + dx * 11, 0f, z + dz * 11, 0f, 1f, 0f)
        overview?.let { camera ->
            val distance = max(campus.mapHeight, campus.mapWidth / aspect) * 1.15f / camera.zoom
            val cx = camera.center.x.toFloat(); val cz = -camera.center.y.toFloat()
            Matrix.setLookAtM(view, 0, cx, distance * .90f, cz + distance * .72f, cx, 0f, cz, 0f, 1f, 0f)
        }
        Matrix.perspectiveM(projection, 0, 53f, aspect, if (overview != null) 5f else .5f, if (overview != null) 6000f else 2200f)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glUseProgram(program)
        box(campus.center.x.toFloat(), -0.5f, -campus.center.y.toFloat(), campus.mapWidth + 200, 1f, campus.mapHeight + 200, 0f, floatArrayOf(.91f,.91f,.91f,1f))
        Matrix.setIdentityM(model, 0)
        // Flat ground layers are ordered explicitly to avoid distant coplanar depth flicker.
        glDisable(GL_DEPTH_TEST); glDepthMask(false)
        draw(shadowMesh, shadowMesh.capacity() / 4, floatArrayOf(.80f,.80f,.80f,1f))
        draw(pathMesh, pathMesh.capacity() / 4, floatArrayOf(.99f,.99f,.99f,1f))
        glDepthMask(true); glEnable(GL_DEPTH_TEST)
        draw(buildingMesh, buildingMesh.capacity() / 4, floatArrayOf(.98f,.98f,.98f,1f))
        val blue = floatArrayOf(.10f,.38f,.98f,1f)
        val routeColor = if (route.origin == RouteOrigin.SHARED) floatArrayOf(22f/255,131f/255,74f/255,1f) else blue
        val done = if (route.origin == RouteOrigin.SHARED) floatArrayOf(.55f,.73f,.62f,1f) else floatArrayOf(.66f,.66f,.66f,1f)
        route.nodes.zipWithNext().forEachIndexed { index, (a, b) ->
            when {
                pose.arrived || index < pose.segment -> road(a.position, b.position, 1.8f, .16f, done)
                index == pose.segment -> {
                    val matched = routePose(route, route.geometricDistance - pose.remaining).position
                    road(a.position, matched, 1.8f, .16f, done)
                    road(matched, b.position, 1.8f, .16f, routeColor)
                }
                else -> road(a.position, b.position, 1.8f, .16f, routeColor)
            }
        }
        if (!pose.arrived && pose.instruction != "목적지까지 직진") {
            val turn = routePose(route, route.geometricDistance - pose.remaining + pose.nextDistance).position
            box(turn.x.toFloat(), .25f, -turn.y.toFloat(), 3.5f, .15f, 3.5f, 45f, floatArrayOf(.22f,.22f,.22f,1f))
        }
        if (showDestination) {
        val goal = route.nodes.last().position
        box(goal.x.toFloat(), 2f, -goal.y.toFloat(), .4f, 4f, .4f, 0f, floatArrayOf(.25f,.25f,.25f,1f))
        box(goal.x.toFloat() + 1.3f, 3.5f, -goal.y.toFloat(), 2.5f, 1.4f, .3f, 0f, floatArrayOf(.25f,.25f,.25f,1f))
        }
        if (!showUserMarker) return
        val markerColor = if (userMarkerReliable) blue else floatArrayOf(.48f,.48f,.48f,1f)
        userAccuracyMetres?.let { radius ->
            Matrix.setIdentityM(model,0)
            Matrix.translateM(model,0,avatarPosition.x.toFloat(),.22f,-avatarPosition.y.toFloat())
            Matrix.scaleM(model,0,radius.toFloat(),1f,radius.toFloat())
            draw(accuracyRing,192,markerColor,GL_LINES)
        }
        // One ground anchor and rotation for the person and the attached direction arrow.
        val ax=avatarPosition.x.toFloat(); val az=-avatarPosition.y.toFloat()
        val rotation=-Math.toDegrees(avatarHeading).toFloat()
        Matrix.setIdentityM(model,0)
        Matrix.translateM(model,0,ax,.27f,az)
        Matrix.rotateM(model,0,rotation,0f,1f,0f)
        if(userHeadingKnown) draw(arrow,9,markerColor)
        val body=if(userMarkerReliable) floatArrayOf(.12f,.19f,.29f,1f) else floatArrayOf(.40f,.42f,.45f,1f)
        val head=if(userMarkerReliable) floatArrayOf(.21f,.29f,.39f,1f) else floatArrayOf(.48f,.50f,.52f,1f)
        fun part(dx: Float,y: Float,dz: Float,rx: Float,ry: Float,rz: Float,color: FloatArray, tilt: Float = 0f) {
            Matrix.setIdentityM(model,0); Matrix.translateM(model,0,ax,0f,az)
            Matrix.rotateM(model,0,rotation,0f,1f,0f); Matrix.translateM(model,0,dx,y,dz)
            Matrix.rotateM(model,0,tilt,0f,0f,1f)
            Matrix.scaleM(model,0,rx,ry,rz)
            draw(avatarSphere,avatarSphere.capacity()/4,color)
        }
        // Overlapping rounded volumes: feet, longer legs, pelvis, torso, neck and head.
        part(-.25f,.37f,-.14f,.25f,.17f,.36f,body)
        part(.25f,.37f,-.14f,.25f,.17f,.36f,body)
        part(-.25f,.94f,0f,.23f,.65f,.27f,body)
        part(.25f,.94f,0f,.23f,.65f,.27f,body)
        part(0f,1.47f,0f,.46f,.34f,.32f,body)
        part(0f,1.96f,0f,.50f,.68f,.34f,body)
        part(0f,2.55f,0f,.22f,.27f,.22f,body)
        // Arms angle down from the shoulders into round, fingerless hands.
        part(-.62f,1.93f,0f,.18f,.55f,.20f,body,-16f)
        part(.62f,1.93f,0f,.18f,.55f,.20f,body,16f)
        part(-.78f,1.43f,-.03f,.23f,.23f,.23f,body)
        part(.78f,1.43f,-.03f,.23f,.23f,.23f,body)
        part(0f,3.03f,0f,.58f,.59f,.56f,head)
    }
    private fun makeAvatarSphere(): java.nio.FloatBuffer {
        val data=mutableListOf<Float>()
        fun vertex(latitude: Double,longitude: Double) {
            val x=cos(latitude)*cos(longitude); val y=sin(latitude); val z=cos(latitude)*sin(longitude)
            val shade=(.70+.30*max(0.0,-.4*x+.7*y+.5*z)).toFloat()
            data.addAll(listOf(x.toFloat(),y.toFloat(),z.toFloat(),shade))
        }
        for(i in 0 until 16) for(j in 0 until 32) {
            val a=-PI/2+i*PI/16; val b=-PI/2+(i+1)*PI/16
            val c=j*2*PI/32; val d=(j+1)*2*PI/32
            vertex(a,c); vertex(b,c); vertex(b,d)
            vertex(a,c); vertex(b,d); vertex(a,d)
        }
        return buffer(data.toFloatArray())
    }

    private fun road(a: Point, b: Point, width: Float, height: Float, color: FloatArray) {
        val length = a.distance(b)
        if (length < .001) return
        box(((a.x + b.x) / 2).toFloat(), height, (-(a.y + b.y) / 2).toFloat(), width, .08f, length.toFloat(), Math.toDegrees(atan2(b.x - a.x, -(b.y - a.y))).toFloat(), color)
    }
    private fun box(x: Float, y: Float, z: Float, w: Float, h: Float, d: Float, rotation: Float, color: FloatArray) {
        Matrix.setIdentityM(model, 0); Matrix.translateM(model, 0, x, y, z)
        Matrix.rotateM(model, 0, rotation, 0f, 1f, 0f); Matrix.scaleM(model, 0, w, h, d)
        draw(cube, 36, color)
    }
    private fun draw(vertices: java.nio.FloatBuffer, count: Int, color: FloatArray, primitive: Int = GL_TRIANGLES) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        glUniformMatrix4fv(matrixUniform, 1, false, mvp, 0); glUniform4fv(colorUniform, 1, color, 0)
        if (count == 0) return
        vertices.position(0); glVertexAttribPointer(positionAttribute, 3, GL_FLOAT, false, 16, vertices); glEnableVertexAttribArray(positionAttribute)
        vertices.position(3); glVertexAttribPointer(shadeAttribute, 1, GL_FLOAT, false, 16, vertices); glEnableVertexAttribArray(shadeAttribute)
        glDrawArrays(primitive, 0, count)
    }
    private fun shader(type: Int, code: String): Int {
        val shader = glCreateShader(type); glShaderSource(shader, code); glCompileShader(shader)
        val result = IntArray(1); glGetShaderiv(shader, GL_COMPILE_STATUS, result, 0)
        check(result[0] != 0) { glGetShaderInfoLog(shader) }
        return shader
    }
    private fun makeBuildingMesh(): java.nio.FloatBuffer {
        val data = mutableListOf<Float>()
        fun vertex(p: Point, y: Float, shade: Float) { data.add(p.x.toFloat()); data.add(y); data.add(-p.y.toFloat()); data.add(shade) }
        for (b in campus.buildings) {
            val points = b.footprint.ifEmpty { listOf(Point(b.center.x-b.width/2,b.center.y-b.depth/2),Point(b.center.x+b.width/2,b.center.y-b.depth/2),Point(b.center.x+b.width/2,b.center.y+b.depth/2),Point(b.center.x-b.width/2,b.center.y+b.depth/2)) }
            val height = b.height.toFloat()
            triangulateFootprint(points).forEach { vertex(points[it], height, 1f) }
            points.indices.forEach { i ->
                val a=points[i]; val c=points[(i+1)%points.size]
                val length = a.distance(c).coerceAtLeast(.001)
                val signedArea = points.indices.sumOf { k -> val p=points[k]; val q=points[(k+1)%points.size]; p.x*q.y-q.x*p.y }
                val sign = if (signedArea >= 0) 1 else -1
                val nx = sign * (c.y-a.y)/length; val ny = -sign * (c.x-a.x)/length
                val shade = (.65 + .25 * max(0.0, nx * -.6 + ny * .8)).toFloat()
                vertex(a,0f,shade); vertex(c,0f,shade); vertex(c,height,shade)
                vertex(a,0f,shade); vertex(c,height,shade); vertex(a,height,shade)
                // Subtle generic floor courses: visual cues, not surveyed facade details.
                val oa=Point(a.x+nx*.04,a.y+ny*.04); val oc=Point(c.x+nx*.04,c.y+ny*.04)
                var level = 3.2f
                while (level < height - .3f) {
                    vertex(oa,level,.57f); vertex(oc,level,.57f); vertex(oc,level+.10f,.57f)
                    vertex(oa,level,.57f); vertex(oc,level+.10f,.57f); vertex(oa,level+.10f,.57f)
                    level += 3.2f
                }
            }
        }
        return buffer(data.toFloatArray())
    }
    /** Directional planar ground shadows, intentionally simplified on the flat campus ground. */
    private fun makeShadowMesh(): java.nio.FloatBuffer {
        val data = mutableListOf<Float>()
        fun vertex(p: Point) { data.add(p.x.toFloat()); data.add(.012f); data.add(-p.y.toFloat()); data.add(1f) }
        for (b in campus.buildings) {
            val p=b.footprint
            if (p.size < 3) continue
            val shifted=p.map { Point(it.x+b.height*.42, it.y-b.height*.32) }
            triangulateFootprint(p).forEach { vertex(shifted[it]) }
            p.indices.forEach { i -> val j=(i+1)%p.size
                vertex(p[i]); vertex(p[j]); vertex(shifted[j])
                vertex(p[i]); vertex(shifted[j]); vertex(shifted[i])
            }
        }
        return buffer(data.toFloatArray())
    }
    private fun makePathMesh(): java.nio.FloatBuffer {
        val data=mutableListOf<Float>()
        fun vertex(x: Double,y: Double) { data.add(x.toFloat()); data.add(.03f); data.add(-y.toFloat()); data.add(1f) }
        for (edge in campus.edges.filter { it.accessible }) {
            val a=campus.byId.getValue(edge.from).position; val b=campus.byId.getValue(edge.to).position
            val length=a.distance(b).coerceAtLeast(.001); val ox=-(b.y-a.y)/length*2; val oy=(b.x-a.x)/length*2
            vertex(a.x+ox,a.y+oy); vertex(a.x-ox,a.y-oy); vertex(b.x-ox,b.y-oy)
            vertex(a.x+ox,a.y+oy); vertex(b.x-ox,b.y-oy); vertex(b.x+ox,b.y+oy)
        }
        return buffer(data.toFloatArray())
    }
    private fun makeCube(): java.nio.FloatBuffer {
        val corners = arrayOf(floatArrayOf(-.5f,-.5f,-.5f), floatArrayOf(.5f,-.5f,-.5f), floatArrayOf(.5f,.5f,-.5f), floatArrayOf(-.5f,.5f,-.5f), floatArrayOf(-.5f,-.5f,.5f), floatArrayOf(.5f,-.5f,.5f), floatArrayOf(.5f,.5f,.5f), floatArrayOf(-.5f,.5f,.5f))
        val faces = arrayOf(intArrayOf(0,1,2,3), intArrayOf(5,4,7,6), intArrayOf(4,0,3,7), intArrayOf(1,5,6,2), intArrayOf(3,2,6,7), intArrayOf(4,5,1,0))
        val shades = floatArrayOf(.8f,.85f,.7f,.9f,1f,.6f)
        val data = mutableListOf<Float>()
        faces.forEachIndexed { i, face -> for (j in intArrayOf(0,1,2,0,2,3)) { data.addAll(corners[face[j]].toList()); data.add(shades[i]) } }
        return buffer(data.toFloatArray())
    }
    private fun buffer(data: FloatArray) = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
}
