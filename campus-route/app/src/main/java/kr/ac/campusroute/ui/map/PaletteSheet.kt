package kr.ac.campusroute.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class CampusPalette(val id: String, val label: String, val background: Color, val selected: Color) {
    IVORY("ivory", "아이보리", Color(0xFFF5F1E8), Color(0xFFECE4D6)),
    MINT("mint", "민트", Color(0xFFEAF4EF), Color(0xFFD4E8DC)),
    BLUE_GRAY("blue_gray", "블루그레이", Color(0xFFEDF2F8), Color(0xFFDCE5F1))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteSheet(selected: CampusPalette, onDismiss: () -> Unit, onSelect: (CampusPalette) -> Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                Text("화면 색상", Modifier.weight(1f), style=MaterialTheme.typography.titleLarge)
                IconButton(onClick=onDismiss, modifier=Modifier.semantics { contentDescription="색상 선택 닫기" }) {
                    Text("×", style=MaterialTheme.typography.headlineSmall)
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup().padding(top=12.dp),
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                CampusPalette.entries.forEach { palette ->
                    val active = palette == selected
                    val shape = RoundedCornerShape(14.dp)
                    Column(Modifier.weight(1f).heightIn(min=110.dp)
                        .background(Color.White, shape)
                        .border(if(active) 2.dp else 1.dp, if(active) MaterialTheme.colorScheme.primary else Color(0xFFD8DDE2), shape)
                        .selectable(selected=active, role=Role.RadioButton, onClick={ onSelect(palette) })
                        .padding(8.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(56.dp).background(palette.background, RoundedCornerShape(8.dp))) {
                            if(active) Text("✓", Modifier.align(Alignment.TopEnd).padding(4.dp), color=MaterialTheme.colorScheme.primary)
                        }
                        Text(palette.label, Modifier.padding(vertical=8.dp), style=MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text("선택하면 바로 적용돼요", Modifier.padding(top=16.dp,bottom=24.dp),
                style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
