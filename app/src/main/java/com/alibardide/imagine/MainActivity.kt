package com.alibardide.imagine

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.alibardide.imagine.databinding.ActivityMainBinding
import com.alibardide.imagine.databinding.DialogProgressBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.IOException
import java.sql.Date
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var progressDialog: AlertDialog
    private var photoUri: Uri? = null
    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { processPhoto(it) }
        }
    private val createMedia =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { outputUri ->
            photoUri?.let { compressPicture(it, outputUri) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        progressDialog = progressAlertDialog()
        binding.mainAbout.setOnClickListener {
            aboutDialog()
        }
        binding.mainPick.setOnClickListener {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.mainCompress.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                photoUri?.let { compressPicture(it) }
            else {
                val createdAt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
                    .format(Date(System.currentTimeMillis()))
                createMedia.launch("imagine-$createdAt")
            }
        }
    }

    private fun compressPicture(input: Uri, output: Uri? = null) {
        progressDialog.show()
        val createdAt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ImageCompressor.compressToMediaStore(
                    context = this@MainActivity,
                    source = input,
                    baseName = "imagine-$createdAt",
                    format = ImageCompressor.Format.JPEG,
                    onSuccess = {
                        Snackbar.make(
                            binding.root,
                            "Image compressed successfully",
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction("Dismiss", null)
                            .show()
                    },
                    onFail = {
                        Snackbar.make(
                            binding.root,
                            it.message.toString(),
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction("Dismiss", null)
                            .show()
                    }
                )
            else
                ImageCompressor.compressToUri(
                    context = this@MainActivity,
                    source = input,
                    outputUri = output!!,
                    format = ImageCompressor.Format.JPEG,
                    onSuccess = {
                        Snackbar.make(
                            binding.root,
                            "Image compressed successfully",
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction("Dismiss", null)
                            .show()
                    },
                    onFail = {
                        Snackbar.make(
                            binding.root,
                            it.message.toString(),
                            Snackbar.LENGTH_INDEFINITE
                        )
                            .setAction("Dismiss", null)
                            .show()
                    }
                )
        }.invokeOnCompletion {
            progressDialog.hide()
        }
    }

    private fun aboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_name) + " ${BuildConfig.VERSION_NAME}")
            .setMessage("Developed by Ali Bardide\nLicensed on Apache 2.0")
            .setPositiveButton("ok", null)
            .setNeutralButton("GitHub") { _: DialogInterface, _: Int ->
                val url = "https://github.com/alibardide5124/imagine.git"
                val i = Intent(Intent.ACTION_VIEW, url.toUri())
                startActivity(i)
            }.create()
            .show()
    }

    private fun progressAlertDialog(): AlertDialog {
        val binding = DialogProgressBinding.inflate(layoutInflater)
        binding.dialogProgressMessage.text = getString(R.string.wait_moment)
        return MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(false)
            .create()
    }

    private fun processPhoto(uri: Uri) {
        try {
            photoUri = uri
            progressDialog.show()
            lifecycleScope.launch {
                binding.mainPicture.setImageDrawable(
                    ImageCompressor.roundDrawableFromUri(
                        this@MainActivity,
                        photoUri!!
                    )
                )
            }.invokeOnCompletion {
                binding.mainCompress.show()
                progressDialog.hide()
            }
        } catch (e: IOException) {
            Snackbar.make(binding.root, "Failed to open picture!", Snackbar.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

}