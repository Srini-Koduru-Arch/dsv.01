package saaicom.tcb.docuscanner

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Define the data structure for our user's profile information
data class UserData(
    val firstName: String,
    val lastName: String,
    val termsAccepted: Boolean
)

// This class handles the actual reading and writing to the device's storage
class UserDataStore(private val context: Context) {

    // Singleton instance of the DataStore
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("user_settings")
        val FIRST_NAME = stringPreferencesKey("first_name")
        val LAST_NAME = stringPreferencesKey("last_name")
        val TERMS_ACCEPTED = booleanPreferencesKey("terms_accepted")
    }

    // A Flow that emits the UserData whenever it changes.
    // Your UI will collect this Flow to stay up-to-date.
    val userDataFlow: Flow<UserData> = context.dataStore.data
        .map { preferences ->
            UserData(
                firstName = preferences[FIRST_NAME] ?: "",
                lastName = preferences[LAST_NAME] ?: "",
                termsAccepted = preferences[TERMS_ACCEPTED] ?: false
            )
        }

    // A suspend function to save the user's data. This should be called from a coroutine.
    suspend fun saveUserData(userData: UserData) {
        context.dataStore.edit { preferences ->
            preferences[FIRST_NAME] = userData.firstName
            preferences[LAST_NAME] = userData.lastName
            preferences[TERMS_ACCEPTED] = userData.termsAccepted
        }
    }
}
