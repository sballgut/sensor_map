package kr.ac.campusroute.location

/** One accepted measurement for map, avatar, camera and progress. Never extrapolates movement. */
class LiveLocationGate(private val originLatitude: Double, private val originLongitude: Double) {
    private var accepted: MapFix? = null
    private var lastSeen = Long.MIN_VALUE
    private var displayed = MapLocationState(LocationCondition.PERMISSION_REQUIRED)
    fun update(input: MapLocationState): MapLocationState {
        if(input.condition==LocationCondition.PERMISSION_REQUIRED) {
            accepted=null; lastSeen=Long.MIN_VALUE; displayed=input.copy(fix=null); return displayed
        }
        val fix=input.fix
        if(!input.canFollow || fix==null || (fix.accuracyMetres ?: Double.MAX_VALUE)>30) {
            displayed=input.copy(condition=if(input.canFollow) LocationCondition.UNCERTAIN else input.condition,fix=accepted)
            return displayed
        }
        if(fix.elapsedRealtimeMs<=lastSeen) return displayed
        lastSeen=fix.elapsedRealtimeMs
        val previous=accepted
        if(previous!=null) {
            val seconds=(fix.elapsedRealtimeMs-previous.elapsedRealtimeMs)/1000.0
            val point=projectWgs84(fix.latitude,fix.longitude,originLatitude,originLongitude)
            val old=projectWgs84(previous.latitude,previous.longitude,originLatitude,originLongitude)
            if(seconds<=0 || point.distance(old)>maxOf(40.0,seconds*6)) {
                displayed=input.copy(condition=LocationCondition.UNCERTAIN,fix=previous)
                return displayed
            }
        }
        accepted=fix; displayed=input
        return displayed
    }
}
