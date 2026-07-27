package ai.withmurph.companion.contacts

import ai.withmurph.companion.core.AddressBookContactSource
import ai.withmurph.companion.core.AddressBookPersonContact
import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAddressBookContacts(
    context: Context,
) : AddressBookContactSource {
    private val applicationContext = context.applicationContext
    private val contentResolver: ContentResolver = applicationContext.contentResolver

    override val readPermission: String = Manifest.permission.READ_CONTACTS

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, readPermission) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun readPersonContacts(): List<AddressBookPersonContact> =
        withContext(Dispatchers.IO) {
            check(hasPermission()) { "Contacts permission is unavailable" }
            queryPersonContacts()
        }

    private fun queryPersonContacts(): List<AddressBookPersonContact> {
        val contacts = ArrayList<AddressBookPersonContact>()
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        )
        val sortOrder = "${ContactsContract.Data.CONTACT_ID} ASC"

        val cursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        ) ?: error("Contacts query was unavailable")
        cursor.use { result ->
            val contactIdColumn = result.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val mimeTypeColumn = result.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val givenNameColumn = result.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            )
            val familyNameColumn = result.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            )
            val phoneColumn = result.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )

            var currentContactId: Long? = null
            var contactsSeen = 0
            var phoneValuesSeen = 0
            var givenName: String? = null
            var familyName: String? = null
            val phoneNumbers = ArrayList<String>(AddressBookProjector.MAX_PHONES_PER_CONTACT)

            fun flushCurrent() {
                if (currentContactId == null) return
                if (!givenName.isNullOrBlank() && phoneNumbers.isNotEmpty()) {
                    contacts += AddressBookPersonContact(
                        givenName = givenName,
                        familyName = familyName,
                        phoneNumbers = phoneNumbers.toList(),
                    )
                }
                givenName = null
                familyName = null
                phoneNumbers.clear()
            }

            while (result.moveToNext()) {
                val contactId = result.getLong(contactIdColumn)
                if (contactId != currentContactId) {
                    flushCurrent()
                    if (contactsSeen >= AddressBookProjector.MAX_CONTACTS) {
                        currentContactId = null
                        break
                    }
                    contactsSeen += 1
                    currentContactId = contactId
                }

                when (result.getString(mimeTypeColumn)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        if (givenName.isNullOrBlank()) {
                            givenName = result.getString(givenNameColumn)
                            familyName = result.getString(familyNameColumn)
                        }
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        if (phoneValuesSeen >= AddressBookProjector.MAX_PHONE_VALUES) break
                        phoneValuesSeen += 1
                        if (phoneNumbers.size < AddressBookProjector.MAX_PHONES_PER_CONTACT) {
                            result.getString(phoneColumn)?.let(phoneNumbers::add)
                        }
                    }
                }
            }
            flushCurrent()
        }
        return contacts
    }
}
