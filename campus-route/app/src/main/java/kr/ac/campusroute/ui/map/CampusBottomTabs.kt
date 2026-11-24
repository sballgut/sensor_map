package kr.ac.campusroute.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CampusBottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),
        shape=RoundedCornerShape(40.dp),color=Color(0xFFF5F7F2),shadowElevation=8.dp) {
        Row(Modifier.selectableGroup().padding(6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("지도","공유 경로").forEachIndexed { index,label ->
                val active=selected==index
                val ink=if(active) Color(0xFF1670FF) else Color(0xFF383B39)
                Column(Modifier.weight(1f)
                    .background(if(active) Color(0xFFE0E5DC) else Color.Transparent,RoundedCornerShape(34.dp))
                    .selectable(selected=active,role=Role.Tab,onClick={onSelect(index)})
                    .padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Canvas(Modifier.size(27.dp)) {
                        val w=size.width; val h=size.height
                        if(index==0) {
                            val pin=Path().apply {
                                moveTo(w*.5f,h*.95f)
                                cubicTo(w*.35f,h*.72f,w*.14f,h*.48f,w*.14f,h*.34f)
                                cubicTo(w*.14f,-h*.08f,w*.86f,-h*.08f,w*.86f,h*.34f)
                                cubicTo(w*.86f,h*.48f,w*.65f,h*.72f,w*.5f,h*.95f); close()
                            }
                            drawPath(pin,ink)
                            drawCircle(Color(0xFFF5F7F2),w*.12f,Offset(w*.5f,h*.31f))
                        } else {
                            val route=Path().apply {
                                moveTo(w*.19f,h*.82f); lineTo(w*.19f,h*.47f)
                                quadraticTo(w*.19f,h*.30f,w*.38f,h*.30f); lineTo(w*.82f,h*.30f)
                                moveTo(w*.62f,h*.10f); lineTo(w*.82f,h*.30f); lineTo(w*.62f,h*.50f)
                            }
                            drawPath(route,ink,style=Stroke(w*.10f,cap=StrokeCap.Round))
                            drawCircle(ink,w*.11f,Offset(w*.19f,h*.82f))
                        }
                    }
                    Text(label,color=ink,style=MaterialTheme.typography.labelLarge,
                        fontWeight=if(active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
