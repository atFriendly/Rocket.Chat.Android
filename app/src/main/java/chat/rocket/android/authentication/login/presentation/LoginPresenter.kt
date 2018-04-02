package chat.rocket.android.authentication.login.presentation

import chat.rocket.android.BuildConfig
import chat.rocket.android.authentication.presentation.AuthenticationNavigator
import chat.rocket.android.core.lifecycle.CancelStrategy
import chat.rocket.android.helper.NetworkHelper
import chat.rocket.android.helper.OauthHelper
import chat.rocket.android.helper.UrlHelper
import chat.rocket.android.infrastructure.LocalRepository
import chat.rocket.android.server.domain.*
import chat.rocket.android.server.domain.model.Account
import chat.rocket.android.server.infraestructure.RocketChatClientFactory
import chat.rocket.android.util.VersionInfo
import chat.rocket.android.util.extensions.*
import chat.rocket.common.RocketChatException
import chat.rocket.common.model.Token
import chat.rocket.common.util.ifNull
import chat.rocket.core.RocketChatClient
import chat.rocket.core.internal.rest.*
import chat.rocket.core.model.Myself
import kotlinx.coroutines.experimental.delay
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TYPE_LOGIN_USER_EMAIL = 0
private const val TYPE_LOGIN_CAS = 1
private const val TYPE_LOGIN_OAUTH = 2
private const val SERVICE_NAME_GITHUB = "github"
private const val SERVICE_NAME_GOOGLE = "google"
private const val SERVICE_NAME_LINKEDIN = "linkedin"
private const val SERVICE_NAME_GILAB = "gitlab"

class LoginPresenter @Inject constructor(private val view: LoginView,
                                         private val strategy: CancelStrategy,
                                         private val navigator: AuthenticationNavigator,
                                         private val tokenRepository: TokenRepository,
                                         private val localRepository: LocalRepository,
                                         private val getAccountsInteractor: GetAccountsInteractor,
                                         settingsInteractor: GetSettingsInteractor,
                                         serverInteractor: GetCurrentServerInteractor,
                                         private val saveAccountInteractor: SaveAccountInteractor,
                                         private val factory: RocketChatClientFactory) {
    // TODO - we should validate the current server when opening the app, and have a nonnull get()
    private val currentServer = serverInteractor.get()!!
    private val client: RocketChatClient = factory.create(currentServer)
    private val settings: PublicSettings = settingsInteractor.get(currentServer)
    private lateinit var usernameOrEmail: String
    private lateinit var password: String
    private lateinit var credentialToken: String
    private lateinit var credentialSecret: String

    fun setupView() {
        setupLoginView()
        setupUserRegistrationView()
        setupCasView()
        setupOauthServicesView()
        checkServerInfo()
    }

    fun authenticateWithUserAndPassword(usernameOrEmail: String, password: String) {
        when {
            usernameOrEmail.isBlank() -> {
                view.alertWrongUsernameOrEmail()
            }
            password.isEmpty() -> {
                view.alertWrongPassword()
            }
            else -> {
                this.usernameOrEmail = usernameOrEmail
                this.password = password
                doAuthentication(TYPE_LOGIN_USER_EMAIL)
            }
        }
    }

    fun authenticateWithCas(token: String) {
        credentialToken = token
        doAuthentication(TYPE_LOGIN_CAS)
    }

    fun authenticateWithOauth(token: String, secret: String) {
        credentialToken = token
        credentialSecret = secret
        doAuthentication(TYPE_LOGIN_OAUTH)
    }

    fun signup() = navigator.toSignUp()

    private fun setupLoginView() {
        if (settings.isLoginFormEnabled()) {
            view.showFormView()
            view.setupLoginButtonListener()
            view.setupGlobalListener()
        } else {
            view.hideFormView()
        }
    }

    private fun setupCasView() {
        if (settings.isCasAuthenticationEnabled()) {
            val token = generateRandomString(17)
            view.setupCasButtonListener(UrlHelper.getCasUrl(settings.casLoginUrl(), currentServer, token), token)
            view.showCasButton()
        }
    }

    private fun setupUserRegistrationView() {
        if (settings.isRegistrationEnabledForNewUsers()) {
            view.showSignUpView()
            view.setupSignUpView()
        }
    }

    private fun setupOauthServicesView() {
        launchUI(strategy) {
            try {
                val services = client.settingsOauth().services
                if (services.isNotEmpty()) {
                    val state = "{\"loginStyle\":\"popup\",\"credentialToken\":\"${generateRandomString(40)}\",\"isCordova\":true}".encodeToBase64()
                    var totalSocialAccountsEnabled = 0

                    if (settings.isFacebookAuthenticationEnabled()) {
//                        //TODO: Remove until we have this implemented
//                        view.enableLoginByFacebook()
//                        totalSocialAccountsEnabled++
                    }
                    if (settings.isGithubAuthenticationEnabled()) {
                        val clientId = getOauthClientId(services, SERVICE_NAME_GITHUB)
                        if (clientId != null) {
                            view.setupGithubButtonListener(OauthHelper.getGithubOauthUrl(clientId, state), state)
                            view.enableLoginByGithub()
                            totalSocialAccountsEnabled++
                        }
                    }
                    if (settings.isGoogleAuthenticationEnabled()) {
                        val clientId = getOauthClientId(services, SERVICE_NAME_GOOGLE)
                        if (clientId != null) {
                            view.setupGoogleButtonListener(OauthHelper.getGoogleOauthUrl(clientId, currentServer, state), state)
                            view.enableLoginByGoogle()
                            totalSocialAccountsEnabled++
                        }
                    }
                    if (settings.isLinkedinAuthenticationEnabled()) {
                        val clientId = getOauthClientId(services, SERVICE_NAME_LINKEDIN)
                        if (clientId != null) {
                            view.setupLinkedinButtonListener(OauthHelper.getLinkedinOauthUrl(clientId, currentServer, state), state)
                            view.enableLoginByLinkedin()
                            totalSocialAccountsEnabled++
                        }
                    }
                    if (settings.isMeteorAuthenticationEnabled()) {
                        //TODO: Remove until we have this implemented
//                        view.enableLoginByMeteor()
//                        totalSocialAccountsEnabled++
                    }
                    if (settings.isTwitterAuthenticationEnabled()) {
                        //TODO: Remove until we have this implemented
//                        view.enableLoginByTwitter()
//                        totalSocialAccountsEnabled++
                    }
                    if (settings.isGitlabAuthenticationEnabled()) {
                        val clientId = getOauthClientId(services, SERVICE_NAME_GILAB)
                        if (clientId != null) {
                            view.setupGitlabButtonListener(OauthHelper.getGitlabOauthUrl(clientId, currentServer, state), state)
                            view.enableLoginByGitlab()
                            totalSocialAccountsEnabled++
                        }
                    }

                    if (totalSocialAccountsEnabled > 0) {
                        view.enableOauthView()
                        if (totalSocialAccountsEnabled > 3) {
                            view.setupFabListener()
                        }
                    } else {
                        view.disableOauthView()
                    }
                } else {
                    view.disableOauthView()
                }
            } catch (exception: RocketChatException) {
                Timber.e(exception)
                view.disableOauthView()
            }
        }
    }

    private fun doAuthentication(loginType: Int) {
        launchUI(strategy) {
            if (NetworkHelper.hasInternetAccess()) {
                view.disableUserInput()
                view.showLoading()
                try {
                    val token = when (loginType) {
                        TYPE_LOGIN_USER_EMAIL -> {
                            if (usernameOrEmail.isEmail()) {
                                client.loginWithEmail(usernameOrEmail, password)
                            } else {
                                if (settings.isLdapAuthenticationEnabled()) {
                                    client.loginWithLdap(usernameOrEmail, password)
                                } else {
                                    client.login(usernameOrEmail, password)
                                }
                            }
                        }
                        TYPE_LOGIN_CAS -> {
                            delay(3, TimeUnit.SECONDS)
                            client.loginWithCas(credentialToken)
                        }
                        TYPE_LOGIN_OAUTH -> {
                            client.loginWithOauth(credentialToken, credentialSecret)
                        }
                        else -> {
                            throw IllegalStateException("Expected TYPE_LOGIN_USER_EMAIL, TYPE_LOGIN_CAS or TYPE_LOGIN_OAUTH")
                        }
                    }
                    val me = client.me()
                    localRepository.save(LocalRepository.CURRENT_USERNAME_KEY, me.username)
                    saveAccount(me)
                    saveToken(token)
                    registerPushToken()
                    navigator.toChatList()
                } catch (exception: RocketChatException) {
                    exception.message?.let {
                        view.showMessage(it)
                    }.ifNull {
                        view.showGenericErrorMessage()
                    }
                } finally {
                    view.hideLoading()
                    view.enableUserInput()
                }
            } else {
                view.showNoInternetConnection()
            }
        }
    }

    private fun saveToken(token: Token) {
        tokenRepository.save(currentServer, token)
    }

    private suspend fun registerPushToken() {
        localRepository.get(LocalRepository.KEY_PUSH_TOKEN)?.let {
            client.registerPushToken(it, getAccountsInteractor.get(), factory)
        }
        // TODO: When the push token is null, at some point we should receive it with
        // onTokenRefresh() on FirebaseTokenService, we need to confirm it.
    }

    private fun getOauthClientId(listMap: List<Map<String, String>>, serviceName: String): String? {
        return listMap.find { map -> map.containsValue(serviceName) }
                ?.get("appId")
    }

    private suspend fun saveAccount(me: Myself) {
        val icon = settings.favicon()?.let {
            UrlHelper.getServerLogoUrl(currentServer, it)
        }
        val logo = settings.wideTile()?.let {
            UrlHelper.getServerLogoUrl(currentServer, it)
        }
        val thumb = UrlHelper.getAvatarUrl(currentServer, me.username!!)
        val account = Account(currentServer, icon, logo, me.username!!, thumb)
        saveAccountInteractor.save(account)
    }

    private fun checkServerInfo() {
        launchUI(strategy) {
            val serverInfo = client.serverInfo()
            val thisServerVersion = serverInfo.version
            val isRequiredVersion = isRequiredServerVersion(thisServerVersion)
            val isRecommendedVersion = isRecommendedServerVersion(thisServerVersion)
            if (isRequiredVersion) {
                if (isRecommendedVersion) {
                    view.alertNotRecommendedVersion()
                } else {
                    Timber.i("Your version is nice! (Requires: 0.62.0, Yours: $thisServerVersion)")
                }
            } else {
                if (!isRecommendedVersion) {
                    view.blockAndAlertNotRequiredVersion()
                    Timber.i("Oops. Looks like your server is out-of-date! Please, upgrade your server for a better experience!")
                }
            }
        }
    }

    private fun isRequiredServerVersion(version: String): Boolean {
        return isMinimumVersion(version, getVersionDistilled(BuildConfig.REQUIRED_SERVER_VERSION))
    }

    private fun isRecommendedServerVersion(version: String): Boolean {
        return isMinimumVersion(version, getVersionDistilled(BuildConfig.RECOMMENDED_SERVER_VERSION))
    }

    private fun isMinimumVersion(version: String, required: VersionInfo): Boolean {
        val thisVersion = getVersionDistilled(version)
        with(thisVersion) {
            if (major < required.major) {
                return false
            } else if (major > required.major) {
                return true
            }
            if (minor < required.minor) {
                return false
            } else if (minor > required.minor) {
                return true
            }
            return update >= required.update
        }
    }

    private fun getVersionDistilled(version: String): VersionInfo {
        var split = version.split("-")
        if (split.isEmpty()) {
            return VersionInfo(0, 0, 0, null, "0.0.0")
        }
        val ver = split[0]
        var release: String? = null
        if (split.size > 1) {
            release = split[1]
        }
        split = ver.split(".")
        val major = getVersionNumber(split, 0)
        val minor = getVersionNumber(split, 1)
        val update = getVersionNumber(split, 2)
        return VersionInfo(
                major = major,
                minor = minor,
                update = update,
                release = release,
                full = version)
    }

    private fun getVersionNumber(split: List<String>, index: Int): Int {
        return try {
            split.getOrNull(index)?.toInt() ?: 0
        } catch (ex: NumberFormatException) {
            0
        }
    }
}