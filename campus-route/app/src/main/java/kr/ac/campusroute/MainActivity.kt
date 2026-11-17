package kr.ac.campusroute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kr.ac.campusroute.ui.shared.SharedRoutesScreen
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.ac.campusroute.data.repository.AssetCampusRepository
import kr.ac.campusroute.ui.CampusViewModel
import kr.ac.campusroute.ui.map.HomeScreen
import kr.ac.campusroute.ui.map.rememberDeviceLocation
import kr.ac.campusroute.ui.navigation.NavigationScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2166F3), background = Color(0xFFF5F5F5), surface = Color.White, secondaryContainer = Color(0xFFF0F0F0), onSecondaryContainer = Color(0xFF303030), surfaceVariant = Color(0xFFF1F1F1), onSurfaceVariant = Color(0xFF555555))) {
                val model: CampusViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = CampusViewModel(AssetCampusRepository(assets).load()) as T
                })
                val state by model.state.collectAsStateWithLifecycle()
                val location = rememberDeviceLocation()
                LaunchedEffect(location.state) { model.updateLocation(location.state) }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                BackHandler(state.navigating) { if (state.mapPreview) model.toggleMap() else model.end() }
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (state.navigating && !state.mapPreview) NavigationScreen(model, state, location.headingDegrees)
                        else if(state.navigating) HomeScreen(model,state,location)
                        else Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if(tab==0) HomeScreen(model,state,location)
                                else SharedRoutesScreen(model,state,location)
                            }
                            NavigationBar {
                                NavigationBarItem(selected=tab==0,onClick={tab=0; model.clearMessage()},icon={Text("⌖")},label={Text("지도")})
                                NavigationBarItem(selected=tab==1,onClick={tab=1; model.clearMessage()},icon={Text("⇄")},label={Text("공유 경로")})
                            }
                        }
                    }
                }
            }
        }
    }
}
