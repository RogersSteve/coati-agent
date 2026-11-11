package bot.coati.model.openai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal interface OpenAiPlanTokenStore {
    fun read(): OpenAiPlanRecord?
    fun write(record: OpenAiPlanRecord)
    fun clear()
    fun readRegistration(): OpenAiPlanRegistration?
    fun getOrCreateHostId(): String
}

internal data class OpenAiPlanRegistration(
    val clientId: String,
    val subject: String,
    val email: String?,
)

internal data class OpenAiPlanRecord(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String?,
    val accessExpiryEpochMs: Long,
    val clientId: String,
    val subject: String,
    val scopes: String,
    val accountId: String,
    val email: String?,
    val planType: String?,
    val fedramp: Boolean,
    val accountsJson: String,
    val modelsJson: String,
    val selectedModel: String?,
) {
    override fun toString(): String = "OpenAiPlanRecord"
}

/**
 * Login material stays in Keystore-backed preferences. The file is excluded from
 * Android backup. There is no plaintext fallback.
 */
internal class SecureOpenAiPlanTokenStore private constructor(
    private val prefs: SharedPreferences,
) : OpenAiPlanTokenStore {
    override fun read(): OpenAiPlanRecord? {
        val access = prefs.getString(ACCESS, null) ?: return null
        val refresh = prefs.getString(REFRESH, null) ?: return null
        val clientId = prefs.getString(CLIENT_ID, null) ?: return null
        val subject = prefs.getString(SUBJECT, null) ?: return null
        val scopes = prefs.getString(SCOPES, null) ?: return null
        val accountId = prefs.getString(ACCOUNT, null) ?: return null
        if (access.isBlank() || refresh.isBlank() || clientId.isBlank() || subject.isBlank() || scopes.isBlank() || accountId.isBlank()) return null
        return OpenAiPlanRecord(
            accessToken = access,
            refreshToken = refresh,
            idToken = prefs.getString(ID_TOKEN, null)?.takeIf { it.isNotBlank() },
            accessExpiryEpochMs = prefs.getLong(EXPIRY, 0L),
            clientId = clientId,
            subject = subject,
            scopes = scopes,
            accountId = accountId,
            email = prefs.getString(EMAIL, null)?.takeIf { it.isNotBlank() },
            planType = prefs.getString(PLAN, null)?.takeIf { it.isNotBlank() },
            fedramp = prefs.getBoolean(FEDRAMP, false),
            accountsJson = prefs.getString(ACCOUNTS, null).orEmpty(),
            modelsJson = prefs.getString(MODELS, null).orEmpty(),
            selectedModel = prefs.getString(MODEL, null)?.takeIf { it.isNotBlank() },
        )
    }

    override fun write(record: OpenAiPlanRecord) {
        val saved = prefs.edit()
            .putString(ACCESS, record.accessToken)
            .putString(REFRESH, record.refreshToken)
            .putString(ID_TOKEN, record.idToken.orEmpty())
            .putLong(EXPIRY, record.accessExpiryEpochMs)
            .putString(CLIENT_ID, record.clientId)
            .putString(SUBJECT, record.subject)
            .putString(SCOPES, record.scopes)
            .putString(ACCOUNT, record.accountId)
            .putString(EMAIL, record.email.orEmpty())
            .putString(PLAN, record.planType.orEmpty())
            .putBoolean(FEDRAMP, record.fedramp)
            .putString(ACCOUNTS, record.accountsJson)
            .putString(MODELS, record.modelsJson)
            .putString(MODEL, record.selectedModel.orEmpty())
            .commit()
        if (!saved) throw OpenAiPlanException("This phone could not save the OpenAI login.")
    }

    override fun clear() {
        val cleared = prefs.edit()
            .remove(ACCESS)
            .remove(REFRESH)
            .remove(ID_TOKEN)
            .remove(EXPIRY)
            .remove(SCOPES)
            .remove(ACCOUNT)
            .remove(PLAN)
            .remove(FEDRAMP)
            .remove(ACCOUNTS)
            .remove(MODELS)
            .remove(MODEL)
            .commit()
        if (!cleared) throw OpenAiPlanException("This phone could not clear the OpenAI login.")
    }

    override fun readRegistration(): OpenAiPlanRegistration? {
        val clientId = prefs.getString(CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val subject = prefs.getString(SUBJECT, null)?.takeIf { it.isNotBlank() } ?: return null
        return OpenAiPlanRegistration(clientId, subject, prefs.getString(EMAIL, null)?.takeIf { it.isNotBlank() })
    }

    override fun getOrCreateHostId(): String {
        prefs.getString(HOST_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = "urn:uuid:${java.util.UUID.randomUUID()}"
        if (!prefs.edit().putString(HOST_ID, generated).commit()) {
            throw OpenAiPlanException("This phone could not save the OpenAI login identity.")
        }
        return generated
    }

    companion object {
        private const val PREFS_NAME = "coati_openai_plan_prefs"
        private const val ACCESS = "access_token"
        private const val REFRESH = "refresh_token"
        private const val ID_TOKEN = "id_token"
        private const val EXPIRY = "access_expiry_epoch_ms"
        private const val CLIENT_ID = "client_id"
        private const val SUBJECT = "subject"
        private const val SCOPES = "scopes"
        private const val HOST_ID = "host_id"
        private const val ACCOUNT = "account_id"
        private const val EMAIL = "email"
        private const val PLAN = "plan_type"
        private const val FEDRAMP = "fedramp"
        private const val ACCOUNTS = "accounts_json"
        private const val MODELS = "models_json"
        private const val MODEL = "selected_model"

        @Volatile
        private var instance: SecureOpenAiPlanTokenStore? = null

        fun get(context: Context): SecureOpenAiPlanTokenStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: open(context.applicationContext).also { instance = it }
            }
        }

        private fun open(context: Context): SecureOpenAiPlanTokenStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return SecureOpenAiPlanTokenStore(prefs)
        }
    }
}
