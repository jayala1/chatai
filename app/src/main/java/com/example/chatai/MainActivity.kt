package com.example.chatai

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatai.ui.theme.ChataiTheme
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

// Retrofit service interface
interface OpenAIApiService {
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun createCompletion(
        @Body body: CreateChatCompletionRequestBody,
        @Header("Authorization") authHeader: String
    ): Response<CreateChatCompletionResponse>
}



// Data classes to match the OpenAI APIs expected JSON structure
data class CreateChatCompletionRequestBody(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int
)

data class Message(
    val role: String, // "user" or "assistant"
    val content: String
)

data class CreateChatCompletionResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: MessageContent
)

data class MessageContent(
    val content: String
)


object RetrofitClient {
    fun create(baseUrl: String): OpenAIApiService {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // Set your desired timeout
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(OpenAIApiService::class.java)
    }
}

class MainActivity : ComponentActivity() {
    private val chatViewModel by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChataiTheme {
                // Pass the ViewModel to the MainScreen
                MainScreen(viewModel = chatViewModel)
            }
        }
    }
}

// ViewModel to manage chat messages and API communication
class ChatViewModel : ViewModel() {
    private val _messages = mutableStateListOf<Message>()
    val messages: List<Message> get() = _messages

    fun sendMessage(userInput: String, apiKey: String, baseUrl: String, model: String) {
        _messages.add(Message(role = "user", content = userInput)) // Add user message to the list

        viewModelScope.launch {
            try {
                val apiService = RetrofitClient.create(baseUrl)
                val authHeader = "Bearer $apiKey"
                val requestBody = CreateChatCompletionRequestBody(
                    model = model,
                    messages = _messages.toList(),
                    maxTokens = 150
                )

                val response = withContext(Dispatchers.IO) {
                    apiService.createCompletion(requestBody, authHeader)
                }

                if (response.isSuccessful) {
                    val apiResponse = response.body()
                    apiResponse?.choices?.forEach { choice ->
                        // Here, remove the unnecessary safe call on 'message'
                        val messageContent = choice.message
                        val aiMessage = Message(role = "assistant", content = messageContent.content)
                        _messages.add(aiMessage)
                    }
                } else {
                    val errorMessage = "Error ${response.code()}: ${response.errorBody()?.string()}"
                    _messages.add(Message(role = "system", content = errorMessage))
                }
            } catch (e: Exception) {
                _messages.add(Message(role = "system", content = "Exception ${e.localizedMessage}"))
            }
        }
    }
}


@Composable
fun MainScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    var baseUrl by remember { mutableStateOf(context.loadSettings().first) }
    var apiKey by remember { mutableStateOf(context.loadSettings().second) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            baseUrl = baseUrl,
            apiKey = apiKey,
            onBaseUrlChanged = { baseUrl = it },
            onApiKeyChanged = { apiKey = it },
            onSaveSettings = {
                context.saveSettings(baseUrl, apiKey)
                showSettings = false
            }
        )
    } else {
        ChatScreen(viewModel = viewModel, onSettingsClicked = { showSettings = true }, apiKey = apiKey, baseUrl = baseUrl, model = "local-model")

    }
}


@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onSettingsClicked: () -> Unit,
    apiKey: String,
    baseUrl: String,
    model: String
) {
    var text by remember { mutableStateOf("") }
    val messages = viewModel.messages // This observes the messages list for changes

    Column(modifier = Modifier.padding(16.dp)) {
        // Settings Button at the top
        Button(onClick = onSettingsClicked, modifier = Modifier.fillMaxWidth()) {
            Text("Settings")
        }

        Spacer(modifier = Modifier.height(8.dp)) // Space between settings button and message list

        // Displaying the list of messages
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(messages.size) { index ->
                val message = messages[index]
                Text(text = "${message.role}: ${message.content}")
            }
        }

        Spacer(modifier = Modifier.height(8.dp)) // Space between message list and text field

        // Text field for user input
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Enter your message") },
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if(text.isNotBlank()) {
                    viewModel.sendMessage(text, apiKey, baseUrl, model)
                    text = "" // Clear the input field after sending
                }
            })
        )

        Spacer(modifier = Modifier.height(8.dp)) // Space between text field and send button

        // Send button
        Button(
            onClick = {
                if(text.isNotBlank()) {
                    viewModel.sendMessage(text, apiKey, baseUrl, model)
                    text = "" // Clear the input field after sending
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send")
        }
    }
}


@Composable
fun SettingsScreen(
    onBaseUrlChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onSaveSettings: () -> Unit,
    baseUrl: String,
    apiKey: String
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChanged,
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onSaveSettings,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Save Settings")
        }
    }
}

// Extension function to save settings
fun Context.saveSettings(baseUrl: String, apiKey: String) {
    val sharedPref = getSharedPreferences("OpenAIChatAppSettings", Context.MODE_PRIVATE) ?: return
    with(sharedPref.edit()) {
        putString("baseUrl", baseUrl)
        putString("apiKey", apiKey)
        apply()
    }
}

// Extension function to load settings
fun Context.loadSettings(): Pair<String, String> {
    val sharedPref = getSharedPreferences("OpenAIChatAppSettings", Context.MODE_PRIVATE) ?: return Pair("", "")
    val baseUrl = sharedPref.getString("baseUrl", "")
    val apiKey = sharedPref.getString("apiKey", "")
    return Pair(baseUrl ?: "", apiKey ?: "")
}
