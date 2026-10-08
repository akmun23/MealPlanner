package com.example.mealplanner

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val AUTH_REDIRECT_URL = "com.mealplan.mealplanner://login-callback"

internal enum class AuthAction {
    SIGN_IN,
    SIGN_UP
}

internal fun authErrorMessage(
    error: Throwable,
    action: AuthAction
): String {
    val raw = buildString {
        var current: Throwable? = error
        while (current != null) {
            if (!current.message.isNullOrBlank()) {
                if (isNotEmpty()) append(' ')
                append(current.message)
            }
            current = current.cause
        }
    }.lowercase()

    return when {
        "invalid login credentials" in raw ||
            "invalid credentials" in raw ->
            "Forkert e-mail eller adgangskode."

        "email not confirmed" in raw ||
            "email_not_confirmed" in raw ->
            "Bekræft din e-mail, før du logger ind."

        "user already registered" in raw ||
            "already been registered" in raw ->
            "Der findes allerede en konto med denne e-mail."

        "invalid email" in raw ||
            "email address" in raw && "invalid" in raw ->
            "Indtast en gyldig e-mailadresse."

        "weak password" in raw ||
            "password should be at least" in raw ->
            "Adgangskoden skal være mindst 6 tegn."

        "rate limit" in raw ||
            "too many requests" in raw ->
            "For mange forsøg. Prøv igen lidt senere."

        "signup" in raw && "disabled" in raw ->
            "Oprettelse af nye konti er ikke tilgængelig lige nu."

        action == AuthAction.SIGN_IN ->
            "Kunne ikke logge ind. Prøv igen."

        else ->
            "Kunne ikke oprette konto. Prøv igen."
    }
}


@Serializable
data class HouseholdMemberDto(
    @SerialName("household_id")
    val householdId: String,

    @SerialName("user_id")
    val userId: String
)


@Serializable
data class HouseholdDto(
    val id: String,
    val name: String,

    @SerialName("join_code")
    val joinCode: String
)

class CloudAccountRepository {

    private val supabase
        get() = SupabaseProvider.client

    suspend fun isLoggedIn(): Boolean {
        val status = supabase.auth.sessionStatus.first {
            it != SessionStatus.Initializing
        }
        return status is SessionStatus.Authenticated
    }

    suspend fun signIn(
        email: String,
        password: String
    ) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signUp(
        email: String,
        password: String
    ) {
        supabase.auth.signUpWith(
            provider = Email,
            redirectUrl = AUTH_REDIRECT_URL
        ) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    suspend fun currentHousehold(): HouseholdDto? {

        val user = supabase.auth.currentUserOrNull()
            ?: return null

        val memberships =
            supabase
                .from("household_members")
                .select {
                    filter {
                        eq("user_id", user.id)
                    }
                }
                .decodeList<HouseholdMemberDto>()

        val membership = memberships.firstOrNull()
            ?: return null

        return supabase
            .from("households")
            .select {
                filter {
                    eq("id", membership.householdId)
                }
            }
            .decodeList<HouseholdDto>()
            .firstOrNull()
    }

    suspend fun renameHousehold(
        householdId: String,
        name: String
    ): HouseholdDto? {
        val clean = name.normalizedDisplayName()
        require(clean.isNotBlank()) { "Navnet må ikke være tomt." }

        supabase.from("households").update(
            {
                set("name", clean)
            }
        ) {
            filter {
                eq("id", householdId)
            }
        }

        return currentHousehold()
    }

    suspend fun createHousehold(
        name: String
    ): HouseholdDto? {

        supabase.postgrest.rpc(
            function = "create_household",
            parameters = buildJsonObject {
                put("p_name", name.normalizedDisplayName())
            }
        )

        return currentHousehold()
    }

    suspend fun joinHousehold(
        code: String
    ): HouseholdDto? {

        supabase.postgrest.rpc(
            function = "join_household",
            parameters = buildJsonObject {
                put("p_join_code", code.trim().uppercase())
            }
        )

        return currentHousehold()
    }
}


private sealed interface CloudScreen {

    data object Loading : CloudScreen

    data object Login : CloudScreen

    data object HouseholdSetup : CloudScreen

    data class Ready(
        val household: HouseholdDto
    ) : CloudScreen

    data object ConnectionIssue : CloudScreen
}


@Composable
fun CloudGate() {

    val repository = remember {
        CloudAccountRepository()
    }

    val scope = rememberCoroutineScope()

    var screen by remember {
        mutableStateOf<CloudScreen>(
            CloudScreen.Loading
        )
    }

    var message by remember {
        mutableStateOf<String?>(null)
    }

    var isBusy by remember {
        mutableStateOf(false)
    }

    var loadAttempt by remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }

    LaunchedEffect(loadAttempt) {
        screen = CloudScreen.Loading
        message = null

        screen = try {
            if (!repository.isLoggedIn()) {
                CloudScreen.Login
            } else {
                val household =
                    repository.currentHousehold()

                if (household == null) {
                    CloudScreen.HouseholdSetup
                } else {
                    CloudScreen.Ready(household)
                }
            }
        } catch (error: Exception) {
            Log.e(
                "MealPlannerStartup",
                "Session or household initialization failed",
                error
            )
            CloudScreen.ConnectionIssue
        }
    }

    when (val current = screen) {

        CloudScreen.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }


        CloudScreen.Login -> {

            LoginScreen(
                message = message,
                isBusy = isBusy,

                onLogin = { email, password ->

                    message = null

                    isBusy = true
                    scope.launch {
                        try {
                            repository.signIn(
                                email = email,
                                password = password
                            )

                            val household =
                                repository.currentHousehold()

                            screen =
                                if (household == null) {
                                    CloudScreen.HouseholdSetup
                                } else {
                                    CloudScreen.Ready(household)
                                }

                        } catch (e: Exception) {
                            message = authErrorMessage(
                                error = e,
                                action = AuthAction.SIGN_IN
                            )
                        } finally {
                            isBusy = false
                        }
                    }
                },

                onCreateAccount = { email, password ->

                    message = null

                    isBusy = true
                    scope.launch {
                        try {
                            repository.signUp(
                                email = email,
                                password = password
                            )

                            if (repository.isLoggedIn()) {

                                screen =
                                    CloudScreen.HouseholdSetup

                            } else {

                                message = "Tjek din mail for at bekræfte kontoen."
                            }

                        } catch (e: Exception) {
                            message = authErrorMessage(
                                error = e,
                                action = AuthAction.SIGN_UP
                            )
                        } finally {
                            isBusy = false
                        }
                    }
                }
            )
        }


        CloudScreen.HouseholdSetup -> {

            HouseholdSetupScreen(
                message = message,
                isBusy = isBusy,

                onCreateHome = { name ->

                    message = null

                    isBusy = true
                    scope.launch {
                        try {
                            val household =
                                repository.createHousehold(name)

                            if (household != null) {

                                screen =
                                    CloudScreen.Ready(household)

                            } else {

                                message = "Kunne ikke indlæse hjemmet."
                            }

                        } catch (_: Exception) {
                            message =
                                "Kunne ikke oprette hjemmet. Prøv igen."
                        } finally {
                            isBusy = false
                        }
                    }
                },

                onJoinHome = { code ->

                    message = null

                    isBusy = true
                    scope.launch {
                        try {
                            val household =
                                repository.joinHousehold(code)

                            if (household != null) {

                                screen =
                                    CloudScreen.Ready(household)

                            } else {

                                message =
                                    "Kunne ikke finde hjemmet."
                            }

                        } catch (_: Exception) {
                            message =
                                "Kunne ikke tilmelde dig hjemmet. Tjek koden og prøv igen."
                        } finally {
                            isBusy = false
                        }
                    }
                },

                onLogout = {

                    scope.launch {

                        repository.signOut()

                        message = null
                        screen = CloudScreen.Login
                    }
                }
            )
        }


        CloudScreen.ConnectionIssue -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Kunne ikke oprette forbindelse",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tjek forbindelsen og prøv igen. Dine data er ikke ændret.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        loadAttempt += 1
                    }
                ) {
                    Text("Prøv igen")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.signOut()
                            message = null
                            screen = CloudScreen.Login
                        }
                    }
                ) {
                    Text("Log ud")
                }
            }
        }

        is CloudScreen.Ready -> {
            MealPlannerApp(
                household = current.household,
                onRenameHousehold = { newName ->
                    try {
                        val updated = repository.renameHousehold(
                            householdId = current.household.id,
                            name = newName
                        )

                        if (updated != null) {
                            screen = CloudScreen.Ready(updated)
                            true
                        } else {
                            false
                        }
                    } catch (_: Exception) {
                        false
                    }
                },
                onLogout = {
                    scope.launch {
                        repository.signOut()
                        message = null
                        screen = CloudScreen.Login
                    }
                }
            )
        }
    }
}


@Composable
private fun LoginScreen(
    message: String?,
    isBusy: Boolean,
    onLogin: (String, String) -> Unit,
    onCreateAccount: (String, String) -> Unit
) {

    var email by remember {
        mutableStateOf("")
    }

    var password by remember {
        mutableStateOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "MealPlanner",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text = "Log ind"
        )

        Spacer(
            modifier = Modifier.height(28.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
            },
            label = {
                Text("E-mail")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
            },
            label = {
                Text("Adgangskode")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation =
                PasswordVisualTransformation()
        )

        if (message != null) {

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isBusy) {
            Spacer(
                modifier = Modifier.height(10.dp)
            )
            Text(
                text = "Arbejder…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(
            modifier = Modifier.height(20.dp)
        )

        Button(
            onClick = {
                onLogin(
                    email,
                    password
                )
            },
            enabled =
                !isBusy &&
                    email.isNotBlank() &&
                    password.length >= 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Log ind")
        }

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        OutlinedButton(
            onClick = {
                onCreateAccount(
                    email,
                    password
                )
            },
            enabled =
                !isBusy &&
                    email.isNotBlank() &&
                    password.length >= 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Opret konto")
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text = "Mindst 6 tegn",
            style = MaterialTheme.typography.bodySmall
        )
    }
}


@Composable
private fun HouseholdSetupScreen(
    message: String?,
    isBusy: Boolean,
    onCreateHome: (String) -> Unit,
    onJoinHome: (String) -> Unit,
    onLogout: () -> Unit
) {

    var homeName by remember {
        mutableStateOf("")
    }

    var joinCode by remember {
        mutableStateOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "Hjem",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Spacer(
            modifier = Modifier.height(22.dp)
        )

        Text(
            text = "Opret hjem",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        OutlinedTextField(
            value = homeName,
            onValueChange = {
                homeName = it
            },
            label = {
                Text("Navn")
            },
            placeholder = {
                Text("Fx Hjem")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        Button(
            onClick = {
                onCreateHome(homeName)
            },
            enabled = !isBusy && homeName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Opret hjem")
        }

        Spacer(
            modifier = Modifier.height(30.dp)
        )

        Text(
            text = "Tilmeld dig et hjem",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        OutlinedTextField(
            value = joinCode,
            onValueChange = {
                joinCode =
                    it.uppercase().take(8)
            },
            label = {
                Text("Invitationskode")
            },
            placeholder = {
                Text("Fx A1B2C3D4")
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(
            modifier = Modifier.height(10.dp)
        )

        OutlinedButton(
            onClick = {
                onJoinHome(joinCode)
            },
            enabled = !isBusy && joinCode.length == 8,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Tilmeld")
        }

        if (message != null) {

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isBusy) {
            Spacer(
                modifier = Modifier.height(10.dp)
            )
            Text(
                text = "Arbejder…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(
            modifier = Modifier.height(18.dp)
        )

        TextButton(
            onClick = onLogout,
            enabled = !isBusy,
            modifier = Modifier.align(
                Alignment.CenterHorizontally
            )
        ) {
            Text("Log ud")
        }
    }
}
