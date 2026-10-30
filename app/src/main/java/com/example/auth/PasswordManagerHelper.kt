package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.autofill.AutofillManager
import android.webkit.WebView
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException

sealed interface CredentialFetchResult {
    data class Success(val username: String, val password: String) : CredentialFetchResult
    data object Cancelled : CredentialFetchResult
    data object NoCredentialsFound : CredentialFetchResult
    data class Error(val message: String) : CredentialFetchResult
}

sealed interface CredentialSaveResult {
    data object Saved : CredentialSaveResult
    data object Cancelled : CredentialSaveResult
    data class Error(val message: String) : CredentialSaveResult
}

/**
 * Integrates both Android's system AutofillManager (for inline WebView autofill bubbles)
 * and Jetpack CredentialManager (for Android Password Manager modal picker & saving credentials).
 */
class PasswordManagerHelper(private val context: Context) {

    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }

    fun configureWebViewForAutofill(webView: WebView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }
    }

    fun isSystemAutofillEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afm = context.getSystemService(AutofillManager::class.java)
            return afm != null && afm.isEnabled && afm.hasEnabledAutofillServices()
        }
        return false
    }

    fun triggerNativeWebViewAutofill(webView: WebView): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afm = context.getSystemService(AutofillManager::class.java)
            if (afm != null && afm.isEnabled) {
                afm.requestAutofill(webView)
                return true
            }
        }
        return false
    }

    fun commitAutofillSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afm = context.getSystemService(AutofillManager::class.java)
            afm?.commit()
        }
    }

    suspend fun requestPasswordFromManager(activity: Activity): CredentialFetchResult {
        return try {
            val getPasswordOption = GetPasswordOption()
            val request = GetCredentialRequest(listOf(getPasswordOption))
            val result = credentialManager.getCredential(
                request = request,
                context = activity
            )
            val credential = result.credential
            if (credential is PasswordCredential) {
                CredentialFetchResult.Success(
                    username = credential.id,
                    password = credential.password
                )
            } else {
                CredentialFetchResult.Error("Unsupported credential type returned.")
            }
        } catch (_: GetCredentialCancellationException) {
            CredentialFetchResult.Cancelled
        } catch (_: NoCredentialException) {
            CredentialFetchResult.NoCredentialsFound
        } catch (e: GetCredentialException) {
            CredentialFetchResult.Error(e.message ?: "Could not access Android Password Manager.")
        } catch (e: Exception) {
            CredentialFetchResult.Error(e.message ?: "Unexpected error accessing Password Manager.")
        }
    }

    suspend fun savePasswordToManager(
        activity: Activity,
        username: String,
        password: String
    ): CredentialSaveResult {
        return try {
            val createPasswordRequest = CreatePasswordRequest(
                id = username.trim(),
                password = password
            )
            credentialManager.createCredential(
                context = activity,
                request = createPasswordRequest
            )
            CredentialSaveResult.Saved
        } catch (_: CreateCredentialCancellationException) {
            CredentialSaveResult.Cancelled
        } catch (e: CreateCredentialException) {
            CredentialSaveResult.Error(e.message ?: "Could not save to Android Password Manager.")
        } catch (e: Exception) {
            CredentialSaveResult.Error(e.message ?: "Unexpected error saving credential.")
        }
    }

    fun openSystemAutofillSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {
        }
        try {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (_: Exception) {
        }
    }
}
