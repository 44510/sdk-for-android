package io.appwrite

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.appwrite.cookies.CookieListener
import io.appwrite.exceptions.AppwriteException
import io.appwrite.extensions.fromJson
import io.appwrite.extensions.onNotificationReceived
import io.appwrite.extensions.toJson
import io.appwrite.models.Notification
import io.appwrite.models.Target
import io.appwrite.models.User
import io.appwrite.services.Account
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.cookieToString
import java.io.IOException
import kotlin.properties.Delegates

class NotificationHandler : FirebaseMessagingService() {

    companion object {
        internal const val ACTION_CLIENT_INIT = "io.appwrite.ACTION_CLIENT_INIT"
        internal const val LISTENER_KEY = "io.appwrite.NotificationHandler"

        internal val httpClient = OkHttpClient()

        internal var client: Client? = null
        internal var account: Account? = null
        internal var providerId: String? = null

        internal var cookieListener: CookieListener? = null

        /**
         * Should notifications be automatically displayed if the app is in the foreground
         */
        var displayForeground = true

        /**
         * The icon to display in the notification
         */
        var displayIcon by Delegates.notNull<Int>()

        /**
         * Should the notification be automatically canceled when the user clicks on it
         */
        var autoCancel = false

        /**
         * The intent to fire when the user clicks on the notification
         */
        var contentIntent: PendingIntent? = null

        /**
         * The channel id to use for the notification
         */
        @RequiresApi(Build.VERSION_CODES.N)
        var channelId = "io.appwrite.notifications"

        /**
         * The channel name to use for the notification
         */
        @RequiresApi(Build.VERSION_CODES.N)
        var channelName = "All Notifications"

        /**
         * The channel description to use for the notification
         */
        @RequiresApi(Build.VERSION_CODES.N)
        var channelDescription = "All notifications"

        /**
         * The channel importance to use for the notification
         */
        @RequiresApi(Build.VERSION_CODES.N)
        var channelImportance = NotificationManager.IMPORTANCE_DEFAULT
    }

    private lateinit var mutex: Mutex

    private lateinit var globalPrefs: SharedPreferences

    private var existingCookies: List<Cookie> = listOf()
    private var newCookies: List<Cookie> = listOf()

    override fun getStartCommandIntent(originalIntent: Intent?): Intent {
        if (originalIntent?.action == ACTION_CLIENT_INIT) {
            return originalIntent
        }

        return super.getStartCommandIntent(originalIntent)
    }

    override fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_CLIENT_INIT) {
            onClientInit()
            return
        }

        super.handleIntent(intent)
    }

    override fun onCreate() {
        super.onCreate()

        globalPrefs = applicationContext.getSharedPreferences(
            Client.GLOBAL_PREFS, Context.MODE_PRIVATE
        )

        displayIcon = resources.getIdentifier(
            "ic_launcher_foreground", "drawable", packageName
        )

        mutex = Mutex()

        if (cookieListener == null) {
            cookieListener = { existing, new ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(javaClass.name, "Fetching FCM registration token failed", task.exception)
                        return@OnCompleteListener
                    }

                    val token = task.result
                    if (token.isNullOrEmpty()) {
                        return@OnCompleteListener
                    }

                    existingCookies = existing
                    newCookies = new

                    onNewToken(token)
                })
            }
        }

        if (client != null) {
            client?.cookieJar?.onSave(LISTENER_KEY, cookieListener!!)
        }
    }

    override fun onNewToken(token: String) {
        runBlocking {
            mutex.withLock {
                pushToken(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Fire callbacks before display so handler can be configured if needed
        onNotificationReceived(
            Notification(
                title = message.notification?.title ?: "",
                body = message.notification?.body ?: "",
                clickAction = message.notification?.clickAction ?: "",
                color = message.notification?.color ?: "",
                icon = message.notification?.icon ?: "",
                imageURL = message.notification?.imageUrl?.toString() ?: "",
                sound = message.notification?.sound ?: "",
                data = message.data
            )
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName, channelImportance
            ).apply {
                description = channelDescription
            }

            // Recreate is a no-op if the channel already exists
            notificationManager.createNotificationChannel(channel)
        }

        if (message.notification != null && displayForeground) {
            val builder = NotificationCompat.Builder(this, "io.appwrite.notifications")
                .setContentTitle(message.notification?.title)
                .setContentText(message.notification?.body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(autoCancel)
                .setContentIntent(contentIntent)

            if (displayIcon != 0) {
                builder.setSmallIcon(displayIcon)
            }

            val notification = builder.build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = packageManager.checkPermission(
                    Manifest.permission.POST_NOTIFICATIONS,
                    packageName
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    Log.w(javaClass.name, "Permission denied, make sure you have requested the POST_NOTIFICATIONS permission")
                }
            }

            notificationManager.notify(message.hashCode(), notification)
        }
    }

    private fun onClientInit() {
        if (client == null) {
            return
        }

        if (account == null) {
            account = Account(client!!)
        }

        client?.cookieJar?.onSave(LISTENER_KEY, cookieListener!!)
    }

    private suspend fun pushToken(token: String) {
        if (client == null) {
            return
        }

        val currentToken = globalPrefs.getString("fcmToken", "") ?: ""
        var currentTargetId = globalPrefs.getString("targetId", "") ?: ""
        val existingUser: User<Map<String, Any>>?
        try {
            existingUser = if (existingCookies.isEmpty() && newCookies.isNotEmpty()) {
                request(
                    "GET",
                    "/account",
                    mapOf("cookie" to newCookies.joinToString("; ") {
                        cookieToString(it, true)
                    })
                )
            } else if (existingCookies.isNotEmpty()) {
                request(
                    "GET",
                    "/account",
                    mapOf("cookie" to existingCookies.joinToString("; ") {
                        cookieToString(it, true)
                    })
                )
            } else {
                account?.get()
            }
        } catch (ex: AppwriteException) {
            Log.d(javaClass.name, "No existing user")
            return
        }

        if (existingUser == null) {
            Log.d(javaClass.name, "No existing user")
            return
        }

        var newUser: User<Map<String, Any>>? = null
        if (newCookies.isNotEmpty()) {
            newUser = request(
                "GET",
                "/account",
                mapOf("cookie" to newCookies.joinToString("; ") {
                    cookieToString(it, true)
                })
            )
            Log.d(javaClass.name, "New user: ${newUser!!.id}")
        }

        if (
            token == currentToken
            && (existingCookies.isNotEmpty() && existingUser.id == newUser?.id)
        ) {
            Log.d(javaClass.name, "Token and user are the same")
            return
        }

        globalPrefs.edit().putString("fcmToken", token).apply()

        try {
            if (existingCookies.isNotEmpty() && existingUser.id != newUser?.id) {
                Log.d(javaClass.name, "User has changed")
                if (currentTargetId.isNotEmpty()) {
                    Log.d(javaClass.name, "Deleting existing target")
                    request<Unit>(
                        "DELETE",
                        "/account/targets/$currentTargetId/push",
                        mapOf("cookie" to existingCookies.joinToString("; ") {
                            cookieToString(it, true)
                        })
                    )
                    globalPrefs.edit().remove("targetId").apply()
                    currentTargetId = ""
                }
            }
        } catch (ex: AppwriteException) {
            Log.e(javaClass.name, "Failed to delete existing target", ex)
        }

        try {
            val target: Target?

            if ((currentTargetId.isEmpty() && existingCookies.isEmpty()) || existingUser.id != newUser?.id) {
                Log.d(javaClass.name, "Creating new target")
                val params = mutableMapOf(
                    "targetId" to ID.unique(),
                    "identifier" to token
                )
                if (providerId != null) {
                    params["providerId"] = providerId!!
                }
                target = request<Target>(
                    "POST",
                    "/account/targets/push",
                    mapOf("cookie" to newCookies.joinToString("; ") {
                        cookieToString(it, true)
                    }),
                    params.toJson().toRequestBody("application/json".toMediaType())
                )
                Log.d(javaClass.name, "New target: ${target?.id}")
            } else {
                Log.d(javaClass.name, "Updating existing target")
                target = account?.updatePushTarget(currentTargetId, token)
                Log.d(javaClass.name, "Updated target: ${target?.id}")
            }

            globalPrefs.edit().putString("targetId", target?.id).apply()
        } catch (ex: AppwriteException) {
            Log.e(javaClass.name, "Failed to push token", ex)
        }

        existingCookies = emptyList()
        newCookies = emptyList()
        Log.d(javaClass.name, "Token pushed")
    }

    private suspend inline fun <reified T> request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: RequestBody? = null
    ): T? {
        val headerBuilder = Headers.Builder()

        for ((key, value) in client?.headers ?: mapOf()) {
            headerBuilder.add(key, value)
        }

        for ((key, value) in headers) {
            headerBuilder.add(key, value)
        }

        val request = Request.Builder()
            .method(method, body)
            .url(client?.endpoint + path)
            .headers(headerBuilder.build())
            .build()

        try {
            return suspendCancellableCoroutine {
                httpClient
                    .newCall(request)
                    .enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: IOException) {
                            Log.e(javaClass.name, "Request failed", e)
                            it.resumeWith(Result.failure(e))
                        }
                        override fun onResponse(
                            call: okhttp3.Call,
                            response: okhttp3.Response
                        ) {
                            if (response.isSuccessful) {
                                val bodyString = response.body?.string()

                                it.resumeWith(Result.success(bodyString?.fromJson()))
                            } else {
                                it.resumeWith(Result.failure(IOException("Request failed with code ${response.code}")))
                            }
                        }
                    })
            }
        } catch (ex: IOException) {
            return null
        }
    }
}