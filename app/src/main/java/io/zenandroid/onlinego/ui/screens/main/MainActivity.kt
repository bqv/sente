package io.zenandroid.onlinego.ui.screens.main

import android.Manifest
import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MenuItem
import android.view.Window
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import io.zenandroid.onlinego.BuildConfig
import io.zenandroid.onlinego.OnlineGoApplication
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.data.model.local.Game
import io.zenandroid.onlinego.data.model.ogs.Size
import io.zenandroid.onlinego.data.model.ogs.Speed
import io.zenandroid.onlinego.databinding.ActivityMainBinding
import io.zenandroid.onlinego.notifications.Bubbles
import io.zenandroid.onlinego.notifications.SynchronizeGamesWork
import io.zenandroid.onlinego.ui.screens.automatch.NewAutomatchChallengeBottomSheet
import io.zenandroid.onlinego.ui.screens.game.GAME_HEIGHT
import io.zenandroid.onlinego.ui.screens.game.GAME_ID
import io.zenandroid.onlinego.ui.screens.game.GAME_WIDTH
import io.zenandroid.onlinego.ui.screens.login.FacebookLoginCallbackActivity
import io.zenandroid.onlinego.data.model.ogs.ChallengeParams
import io.zenandroid.onlinego.ui.screens.newchallenge.NewChallengeBottomSheet
import io.zenandroid.onlinego.ui.views.BoardView
import io.zenandroid.onlinego.utils.hide
import io.zenandroid.onlinego.utils.show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

class MainActivity : AppCompatActivity(), MainContract.View {
    companion object {
        var isInForeground = false
        val TAG = MainActivity::class.java.simpleName
    }

    private val analytics = OnlineGoApplication.instance.analytics

    private lateinit var binding: ActivityMainBinding

    private var requestNotificationPermissionLauncher: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
		if (!it) {
			return@registerForActivityResult
		} 

        if (Build.VERSION.SDK_INT >= Bubbles.MIN_SDK_BUBBLES) {
            if (!hasBubblePermissions()) {
                requestBubblePermissions()
            }
        }
	}
    private var requestBubblePermissionLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (hasBubblePermissions()) {
		}
	}

    private val presenter: MainPresenter by lazy { MainPresenter(this, get(), get(), get(), get()) }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            findNavController(R.id.fragment_container).navigateUp()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Handler(Looper.getMainLooper()).post {
            ViewCompat.getWindowInsetsController(binding.root)?.isAppearanceLightStatusBars =
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO
        }

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        binding.bottomNavigation.setupWithNavController(navHostFragment.navController)
        navHostFragment.navController.addOnDestinationChangedListener { _, destination, arguments ->
            binding.apply {
                bottomNavigation.apply {
                    val shouldBeVisible =
                        destination.id in arrayOf(
                        R.id.myGames,
                        R.id.explore,
                        R.id.learn,
                        R.id.settings
                    ) || (destination.id == R.id.stats && arguments?.isEmpty != false)
                    if(shouldBeVisible) {
                        animate().alpha(1f).setUpdateListener {
                            if (it.animatedFraction == 1f) {
                                show()
                            }
                        }
                        .setDuration(150)
                        .start()
                    } else {
                        animate().alpha(0f)
                            .setUpdateListener {
                                if (it.animatedFraction == 1f) {
                                    hide()
                                }
                            }
                            .setDuration(70)
                            .start()
                    }
                    setOnNavigationItemReselectedListener { }
                }
            }
        }

        createNotificationChannel()
        scheduleNotificationJob()

        packageManager.setComponentEnabledSetting(
            ComponentName(this, FacebookLoginCallbackActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        BoardView.preloadResources(resources)

        intent?.data?.let { data ->
            // Figure out what to do based on the intent type
            if (intent?.scheme?.startsWith("http") == true) {
                // Handle intents with remote data ...
                Log.d("MainActivity", "Recieved remote intent ${data}")
                navHostFragment.navController.navigate(R.id.aiGameFragment, Bundle().apply {
                    putString("SGF_REMOTE", data.toString())
                })
            } else if (intent?.type == "application/x-go-sgf") {
                // Handle intents with local data ...
                Log.d("MainActivity", "Recieved local intent ${data}")
                navHostFragment.navController.navigate(R.id.aiGameFragment, Bundle().apply {
                    putString("SGF_LOCAL", data.toString())
                })
            }
        }
    }

    private fun scheduleNotificationJob() {
        SynchronizeGamesWork.schedule()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            listOf(
                    NotificationChannelGroup("correspondence", "Correspondence"),
                    NotificationChannelGroup("live", "Live"),
                    NotificationChannelGroup("blitz", "Blitz"),
            ).map(notificationManager::createNotificationChannelGroup)

            notificationManager.createNotificationChannels(
                    listOf(
                            NotificationChannel("active_correspondence_games", "Your Turn", NotificationManager.IMPORTANCE_HIGH).apply {
                                setGroup("correspondence")
                                enableLights(true)
                                lightColor = Color.WHITE
                                enableVibration(true)
                                vibrationPattern = longArrayOf(0, 200, 0, 200)
                                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                                setAllowBubbles(true)
                            },
                            NotificationChannel("active_live_games", "Your Turn", NotificationManager.IMPORTANCE_HIGH).apply {
                                setGroup("live")
                                enableLights(true)
                                lightColor = Color.WHITE
                                enableVibration(true)
                                vibrationPattern = longArrayOf(0, 200, 0, 200)
                                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                                setAllowBubbles(true)
                            },
                            NotificationChannel("active_blitz_games", "Your Turn", NotificationManager.IMPORTANCE_HIGH).apply {
                                setGroup("blitz")
                                enableLights(true)
                                lightColor = Color.WHITE
                                enableVibration(true)
                                vibrationPattern = longArrayOf(0, 200, 0, 200)
                                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                                setAllowBubbles(true)
                            },
                            NotificationChannel("active_games", "Your Turn", NotificationManager.IMPORTANCE_HIGH).apply {
                                enableLights(true)
                                lightColor = Color.WHITE
                                enableVibration(true)
                                vibrationPattern = longArrayOf(0, 200, 0, 200)
                                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                                setAllowBubbles(true)
                            },
                            NotificationChannel("challenges", "Challenges", NotificationManager.IMPORTANCE_LOW).apply {
                                enableLights(true)
                                lightColor = Color.WHITE
                                enableVibration(true)
                                vibrationPattern = longArrayOf(0, 200, 0, 200)
                            },
                            NotificationChannel("logout", "Logout", NotificationManager.IMPORTANCE_LOW).apply {
                                enableLights(false)
                                enableVibration(false)
                            }
                    )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun askForNotificationsPermission(delayed: Boolean) {
        CoroutineScope(Dispatchers.Main).launch {
            if(delayed) {
                delay(5000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onResume() {
        presenter.subscribe()

        isInForeground = true
        super.onResume()
    }

    override fun showLogin() {
        findNavController(R.id.fragment_container).apply {
            if(currentDestination?.id != R.id.onboardingFragment) {
                navigate(R.id.onboardingFragment)
            }
        }
    }

    override fun showMyGames() {
        findNavController(R.id.fragment_container).apply {
            if(currentDestination?.id != R.id.myGames) {
                navigate(R.id.myGames)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        presenter.unsubscribe()
        isInForeground = false
    }

    override fun navigateToGameScreen(game: Game) {
        findNavController(R.id.fragment_container)
                .navigate(
                        R.id.gameFragment,
                        bundleOf(
                            GAME_ID to game.id,
                            GAME_WIDTH to game.width,
                            GAME_HEIGHT to game.height,
                        ),
                        NavOptions.Builder()
                                .setLaunchSingleTop(true)
                                .build()
                )
    }

    override fun showError(msg: String?) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    fun onAutomatchSearchClicked(speed: Speed, sizes: List<Size>) {
        val params = Bundle().apply {
            putString("SPEED", speed.toString())
            putString("SIZE", sizes.joinToString { it.toString() })
        }
        analytics.logEvent("new_game_search", params)
        presenter.onStartSearch(sizes, speed)
    }

    fun onAutoMatchSearch() {
        NewAutomatchChallengeBottomSheet().show(supportFragmentManager, "BOTTOM_SHEET")
    }

    fun onNavigateToSupport() {
        findNavController(R.id.fragment_container).navigate(R.id.supporterFragment)
    }

    fun onCustomGameSearch() {
        NewChallengeBottomSheet().show(supportFragmentManager, "BOTTOM_SHEET")
    }

    fun onNewChallengeSearchClicked(challengeParams: ChallengeParams) {
        analytics.logEvent("bot_challenge", null)
        presenter.onNewBotChallenge(challengeParams)
    }

	@RequiresApi(Bubbles.MIN_SDK_BUBBLES)
	private fun hasBubblePermissions(): Boolean {
		if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
			return false
		}

		return Bubbles.canDisplayBubbles(applicationContext, "active_correspondence_games")
	}

	@RequiresApi(Bubbles.MIN_SDK_BUBBLES)
	private fun requestBubblePermissions() {
		// Note that the notification channel must be created before we launch the Bubbles settings activity.
		createNotificationChannel()
		requestBubblePermissionLauncher.launch(Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
			.putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
	}

	private fun hasPermission(permission: String): Boolean {
		return ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED
	}

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!BuildConfig.DEBUG) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP ->
                io.zenandroid.onlinego.utils.LogcatPopup(this).show()
            KeyEvent.KEYCODE_VOLUME_DOWN -> {}
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
