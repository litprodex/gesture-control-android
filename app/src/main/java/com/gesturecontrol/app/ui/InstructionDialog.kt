import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gesturecontrol.app.R // Upewnij się, że importujesz R ze swojego pakietu

@Composable
fun InstructionDialog(onDismiss: () -> Unit) {
    // Lista dodanych wcześniej obrazków z instrukcją
    val instructionImages = listOf(
        R.drawable.instrukcja_1,
        R.drawable.instrukcja_2,
        R.drawable.instrukcja_3,
        R.drawable.instrukcja_4,
        R.drawable.instrukcja_5
    )
    
    val pagerState = rememberPagerState(pageCount = { instructionImages.size })

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false) // Pozwala na szerszy dialog
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Instrukcja uruchomienia (${pagerState.currentPage + 1}/${instructionImages.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                // Przewijana karuzela obrazków
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    Image(
                        painter = painterResource(id = instructionImages[page]),
                        contentDescription = "Krok ${page + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Zamknij")
                }
            }
        }
    }
}