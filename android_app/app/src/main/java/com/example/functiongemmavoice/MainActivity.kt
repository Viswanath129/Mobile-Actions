package com.example.functiongemmavoice

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.example.functiongemmavoice.speech.SpeechManager
import com.example.functiongemmavoice.ui.ChatGPTAssistantScreen
import com.example.functiongemmavoice.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity(), ComponentCallbacks2 {

    private val viewModel: AssistantViewModel by viewModels()
    private lateinit var speechManager: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speechManager = SpeechManager(applicationContext)
        speechManager.initTts()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2563EB),
                    background = Color(0xFFF9F9F9),
                    surface = Color.White
                )
            ) {
                val uiState by viewModel.uiState.collectAsState()
                val listState = rememberLazyListState()

                val modelPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        viewModel.importModel(uri)
                    }
                }

                val micPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.setListeningState(true, "Listening…")
                        speechManager.startListening(
                            activity = this@MainActivity,
                            languageChoice = uiState.currentSpeechLanguage,
                            onText = { text ->
                                viewModel.setListeningState(false, "Speech recognized")
                                viewModel.submitCommand(text) { reply ->
                                    speechManager.speak(reply, uiState.currentSpeechLanguage)
                                }
                            },
                            onError = { err ->
                                viewModel.setListeningState(false, err)
                            },
                            onStatus = { s ->
                                viewModel.setListeningState(true, s)
                            }
                        )
                    } else {
                        viewModel.setListeningState(false, "Microphone permission denied")
                    }
                }

                ChatGPTAssistantScreen(
                    uiState = uiState,
                    onSendCommand = { cmd ->
                        viewModel.submitCommand(cmd) { reply ->
                            speechManager.speak(reply, uiState.currentSpeechLanguage)
                        }
                    },
                    onMicClick = {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onNewChatClick = {
                        viewModel.startNewConversation()
                    },
                    onImportModelClick = {
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    onLanguageChange = { lang ->
                        viewModel.setSpeechLanguage(lang)
                    },
                    onConfirmAction = { action ->
                        viewModel.confirmAction(action) { reply ->
                            speechManager.speak(reply, uiState.currentSpeechLanguage)
                        }
                    },
                    onCancelAction = { action ->
                        viewModel.cancelAction(action)
                    },
                    onRetryCommand = { cmd ->
                        viewModel.submitCommand(cmd) { reply ->
                            speechManager.speak(reply, uiState.currentSpeechLanguage)
                        }
                    },
                    listState = listState
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            viewModel.onTrimMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        viewModel.onTrimMemory()
    }

    override fun onDestroy() {
        speechManager.shutdown()
        super.onDestroy()
    }
}
