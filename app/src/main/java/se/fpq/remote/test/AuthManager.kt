package se.fpq.remote.test

import android.content.Context
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import android.content.Intent
import android.util.Log

private const val TAG = "AuthManager"
private const val AUTH_REQUEST_CODE = 1337
// Request all necessary scopes for full Spotify access
private val SCOPES = arrayOf(
    "user-library-read",      // Read saved tracks
    "user-read-private",      // Read user profile
    "streaming"               // Control playback
)

class AuthManager(private val activity: android.app.Activity) {
    companion object {
        private var accessToken: String? = null
        
        fun getAccessToken(): String? = accessToken
        fun setAccessToken(token: String) {
            accessToken = token
        }
        fun isAuthenticated(): Boolean = accessToken != null
    }
    
    fun startAuthentication(clientId: String, redirectUri: String): Intent {
        Log.d(TAG, "🔐 Starting Spotify authentication...")
        
        val request = AuthorizationRequest.Builder(clientId, AuthorizationResponse.Type.TOKEN, redirectUri)
            .setScopes(SCOPES)
            .build()
        
        return AuthorizationClient.createLoginActivityIntent(activity, request)
    }
    
    fun handleAuthenticationResponse(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == AUTH_REQUEST_CODE) {
            val response = AuthorizationClient.getResponse(resultCode, data)
            
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    accessToken = response.accessToken
                    Log.d(TAG, "✅ Authentication successful!")
                    return true
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e(TAG, "❌ Authentication error: ${response.error}")
                    return false
                }
                else -> {
                    Log.w(TAG, "⚠️ Authentication cancelled")
                    return false
                }
            }
        }
        return false
    }
    
    fun getAuthRequestCode(): Int = AUTH_REQUEST_CODE
}

