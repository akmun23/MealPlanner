package com.example.mealplanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val AUTH_REDIRECT_URL = "com.example.mealplanner://login-callback"


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


@Serializable
data class CreateHouseholdParams(
    @SerialName("p_name")
    val name: String
)


@Serializable
data class JoinHouseholdParams(
    @SerialName("p_join_code")
    val joinCode: String
)


class CloudAccountRepository {

    private val supabase = SupabaseProvider.client

    fun isLoggedIn(): Boolean {
        return supabase.auth.currentSessionOrNull() != null
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

    suspend fun createHousehold(
        name: String
    ): HouseholdDto? {

        supabase.postgrest.rpc(
            function = "create_household",
            parameters = buildJsonObject {
                put("p_name", name.trim())
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

    LaunchedEffect(Unit) {

        screen =
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

                onLogin = { email, password ->

                    message = null

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

                            message =
                                e.message
                                    ?: "Kunne ikke logge ind."
                        }
                    }
                },

                onCreateAccount = { email, password ->

                    message = null

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

                                message =
                                    "Kontoen er oprettet. " +
                                            "Tjek din e-mail og bekræft kontoen. " +
                                            "Gå derefter tilbage til appen og log ind."
                            }

                        } catch (e: Exception) {

                            message =
                                e.message
                                    ?: "Kunne ikke oprette bruger."
                        }
                    }
                }
            )
        }


        CloudScreen.HouseholdSetup -> {

            HouseholdSetupScreen(
                message = message,

                onCreateHome = { name ->

                    message = null

                    scope.launch {

                        try {

                            val household =
                                repository.createHousehold(name)

                            if (household != null) {

                                screen =
                                    CloudScreen.Ready(household)

                            } else {

                                message =
                                    "Hjemmet blev oprettet, " +
                                            "men kunne ikke indlæses."
                            }

                        } catch (e: Exception) {

                            message =
                                e.message
                                    ?: "Kunne ikke oprette hjem."
                        }
                    }
                },

                onJoinHome = { code ->

                    message = null

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

                        } catch (e: Exception) {

                            message =
                                e.message
                                    ?: "Kunne ikke tilmelde hjem."
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


        is CloudScreen.Ready -> {

            Column(
                modifier = Modifier.fillMaxSize()
            ) {

                HouseholdHeader(
                    household = current.household,

                    onLogout = {

                        scope.launch {

                            repository.signOut()

                            message = null
                            screen = CloudScreen.Login
                        }
                    }
                )

                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    MealPlannerApp()
                }
            }
        }
    }
}


@Composable
private fun LoginScreen(
    message: String?,
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
            text = "Log ind for at dele madplan, lager og opskrifter."
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

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp)
                )
            }
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
                email.isNotBlank() &&
                        password.length >= 6,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Opret ny bruger")
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text = "Adgangskoden skal være mindst 6 tegn.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}


@Composable
private fun HouseholdSetupScreen(
    message: String?,
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
            text = "Velkommen",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        Text(
            text =
                "Opret et nyt hjem, eller tilmeld dig " +
                        "et hjem der allerede findes."
        )

        Spacer(
            modifier = Modifier.height(28.dp)
        )

        Text(
            text = "Opret nyt hjem",
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
                Text("Navn på hjem")
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
            enabled = homeName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Opret hjem")
        }

        Spacer(
            modifier = Modifier.height(30.dp)
        )

        Text(
            text = "Tilmeld eksisterende hjem",
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
                Text("Hjemmekode")
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
            enabled = joinCode.length == 8,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Tilmeld hjem")
        }

        if (message != null) {

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        Spacer(
            modifier = Modifier.height(18.dp)
        )

        TextButton(
            onClick = onLogout,
            modifier = Modifier.align(
                Alignment.CenterHorizontally
            )
        ) {
            Text("Log ud")
        }
    }
}


@Composable
private fun HouseholdHeader(
    household: HouseholdDto,
    onLogout: () -> Unit
) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp
            ),
        shape = RoundedCornerShape(16.dp)
    ) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 14.dp,
                    vertical = 8.dp
                ),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                modifier = Modifier.weight(1f)
            ) {

                Text(
                    text = household.name,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text =
                        "Hjemmekode: ${household.joinCode}",
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }

            TextButton(
                onClick = onLogout
            ) {
                Text("Log ud")
            }
        }
    }
}