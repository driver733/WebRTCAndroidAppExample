package com.android.webrtc.example.view

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.android.webrtc.example.R
import com.android.webrtc.example.databinding.ActivityMainBinding
import kotlin.concurrent.fixedRateTimer

class MainActivity : AppCompatActivity() {

    private val binding: ActivityMainBinding by lazy(LazyThreadSafetyMode.NONE) {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
    }

    // !!! Don't do it in prod, it's workaround to avoid memory leaks
    // We don't care for any cache, unfinished jobs, etc...
    override fun onBackPressed() {
        val fragmentsCount =
            supportFragmentManager.fragments.firstOrNull()?.childFragmentManager?.backStackEntryCount
        if (fragmentsCount == 0) {
            // exit app
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        super.onBackPressed()
    }
}