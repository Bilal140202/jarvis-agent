package com.jarvis.agent.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log

/**
 * Communication Service — handles calls, SMS, and contact lookup.
 */
class CommunicationService(private val context: Context) {

    companion object {
        private const val TAG = "CommunicationService"
    }

    data class Contact(val name: String, val phoneNumber: String)

    /**
     * Make a phone call to a number.
     */
    fun makeCall(phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Calling: $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Call failed", e)
            false
        }
    }

    /**
     * Make a phone call by contact name.
     */
    fun makeCallByName(contactName: String): Boolean {
        val contacts = searchContacts(contactName)
        if (contacts.isEmpty()) {
            Log.w(TAG, "Contact not found: $contactName")
            return false
        }
        return makeCall(contacts.first().phoneNumber)
    }

    /**
     * Send an SMS message.
     */
    fun sendSms(contactName: String, message: String): Boolean {
        val contacts = searchContacts(contactName)
        if (contacts.isEmpty()) {
            if (contactName.matches(Regex("[+]?\\d{7,15}"))) {
                return sendSmsDirect(contactName, message)
            }
            Log.w(TAG, "Contact not found: $contactName")
            return false
        }
        return sendSmsDirect(contacts.first().phoneNumber, message)
    }

    private fun sendSmsDirect(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = getSmsManager()
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            Log.i(TAG, "SMS sent to $phoneNumber")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed, trying intent fallback", e)
            // Fallback: open SMS app
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("sms_body", message)
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "SMS intent fallback also failed", e2)
                false
            }
        }
    }

    /**
     * Get SmsManager compatible with API 31+.
     */
    private fun getSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    /**
     * Search contacts by name.
     */
    fun searchContacts(query: String): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: continue
                    contacts.add(Contact(name, number))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact search failed", e)
        }
        return contacts.distinctBy { it.phoneNumber }
    }
}
