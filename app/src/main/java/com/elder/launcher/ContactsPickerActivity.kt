package com.elder.launcher

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.elder.launcher.databinding.ActivityContactsPickerBinding

class ContactsPickerActivity : BaseActivity() {

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_PHOTO_URI = "extra_photo_uri"
    }

    private lateinit var binding: ActivityContactsPickerBinding

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) resolveContact(uri) else finish()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        pickLauncher.launch(intent)
    }

    private fun resolveContact(uri: Uri) {
        val cr = contentResolver
        var name = ""
        var photoUri: String? = null
        var phone = ""
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(c.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME))
                    val hasPhone = c.getInt(c.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
                    val id = c.getLong(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                    if (c.getColumnIndex(ContactsContract.Contacts.PHOTO_URI) >= 0) {
                        photoUri = c.getString(c.getColumnIndex(ContactsContract.Contacts.PHOTO_URI))
                    }
                    if (hasPhone) phone = queryPhone(id)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取联系人失败", Toast.LENGTH_SHORT).show()
        }
        if (phone.isEmpty()) {
            Toast.makeText(this, "该联系人没有电话号码", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val result = Intent().apply {
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_PHONE, phone)
            if (photoUri != null) putExtra(EXTRA_PHOTO_URI, photoUri)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun queryPhone(contactId: Long): String {
        val cr = contentResolver
        var result = ""
        try {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
                arrayOf(contactId.toString()),
                null
            )?.use { c: Cursor ->
                if (c.moveToFirst()) result = c.getString(0) ?: ""
            }
        } catch (_: Exception) {
        }
        return result.replace(" ", "").replace("-", "")
    }
}
