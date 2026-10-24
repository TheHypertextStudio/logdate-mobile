package app.logdate.client.domain.account

import app.logdate.client.datastore.KeyValueStorage
import io.github.aakira.napier.Napier

/**
 * Data class containing account setup information.
 */
data class AccountSetupData(
    val username: String = "",
    val displayName: String = "",
    val email: String? = null,
)

/**
 * Use case to retrieve temporary account setup data stored during the onboarding flow.
 * This data is needed between screens in the account creation process.
 */
class GetAccountSetupDataUseCase(
    private val keyValueStorage: KeyValueStorage,
) {
    companion object {
        private const val KEY_USERNAME = "account_setup_username"
        private const val KEY_DISPLAY_NAME = "account_setup_display_name"
        private const val KEY_EMAIL = "account_setup_email"
    }

    /**
     * Retrieves the account setup data from temporary storage.
     * @param action Optional action to perform (Get, Save, Clear)
     * @param data Optional data to save (only used with Save action)
     * @return AccountSetupData containing the information entered during account setup when using Get action
     */
    suspend operator fun invoke(
        action: Action = Action.Get,
        data: AccountSetupData? = null,
    ): AccountSetupData =
        when (action) {
            Action.Get -> {
                getAccountData()
            }

            is Action.Save -> {
                if (data != null) {
                    saveAccountData(data)
                }
                data ?: AccountSetupData()
            }

            Action.Clear -> {
                clearAccountData()
                AccountSetupData()
            }
        }

    /**
     * Actions that can be performed on account setup data
     */
    sealed class Action {
        object Get : Action()

        object Save : Action()

        object Clear : Action()
    }

    private suspend fun getAccountData(): AccountSetupData =
        try {
            val username = keyValueStorage.getString(KEY_USERNAME) ?: ""
            val displayName = keyValueStorage.getString(KEY_DISPLAY_NAME) ?: ""
            val email = keyValueStorage.getString(KEY_EMAIL)

            AccountSetupData(
                username = username,
                displayName = displayName,
                email = email,
            )
        } catch (e: Exception) {
            Napier.e("Failed to retrieve account setup data", e)
            AccountSetupData()
        }

    private suspend fun saveAccountData(data: AccountSetupData) {
        try {
            keyValueStorage.putString(KEY_USERNAME, data.username)
            keyValueStorage.putString(KEY_DISPLAY_NAME, data.displayName)
            if (data.email != null) {
                keyValueStorage.putString(KEY_EMAIL, data.email)
            } else {
                keyValueStorage.remove(KEY_EMAIL)
            }
            Napier.i("Account setup data saved")
        } catch (e: Exception) {
            Napier.e("Failed to save account setup data", e)
        }
    }

    private suspend fun clearAccountData() {
        try {
            keyValueStorage.remove(KEY_USERNAME)
            keyValueStorage.remove(KEY_DISPLAY_NAME)
            keyValueStorage.remove(KEY_EMAIL)
            Napier.i("Account setup data cleared")
        } catch (e: Exception) {
            Napier.e("Failed to clear account setup data", e)
        }
    }
}
