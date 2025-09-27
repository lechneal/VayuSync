package com.lechneralexander.vayusync.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.lechneralexander.vayusync.copy.CopyService

class ConfirmationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION_TO_CONFIRM = "action_to_confirm"
        const val ACTION_CONFIRM_CANCEL_COPY = "confirm_cancel_copy"

        fun newIntent(context: Context, actionToConfirm: String): Intent {
            return Intent(context, ConfirmationActivity::class.java).apply {
                putExtra(EXTRA_ACTION_TO_CONFIRM, actionToConfirm)
                // Add FLAG_ACTIVITY_NEW_TASK because we might be starting it from a Service context
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent.getStringExtra(EXTRA_ACTION_TO_CONFIRM)

        if (action == ACTION_CONFIRM_CANCEL_COPY) {
            showCancelCopyDialog()
        } else {
            // Unknown action, just finish
            finish()
        }
    }

    private fun showCancelCopyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cancel Copy?")
            .setMessage("This will stop copying the remaining files.")
            .setPositiveButton("Cancel Copy") { _, _ ->
                CopyService.cancel(application)
                Toast.makeText(this, "Copy cancelled", Toast.LENGTH_SHORT).show()
                finish() // Close this confirmation activity
            }
            .setNegativeButton("Keep Copying") { _, _ ->
                finish()
            }
            .setOnCancelListener { // Handle back button or tap outside
                finish()
            }
            .show()
    }
}
