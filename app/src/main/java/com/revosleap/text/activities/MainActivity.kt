package com.revosleap.text.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.ContactsContract
import android.support.design.widget.BottomSheetBehavior
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.telephony.SmsManager
import android.view.View
import android.widget.Toast
import com.revosleap.text.Application
import com.revosleap.text.R
import com.revosleap.text.adapters.MessagesAdapter
import com.revosleap.text.adapters.SendingAdapter
import com.revosleap.text.dialogs.SentRecipients
import com.revosleap.text.interfaces.ContactList
import com.revosleap.text.interfaces.MessageClicked
import com.revosleap.text.interfaces.OnContactClicked
import com.revosleap.text.models.ContactModel
import com.revosleap.text.models.Contacts
import com.revosleap.text.models.SentMessages
import com.revosleap.text.models.SentMessages_
import com.revosleap.text.utils.Blur
import com.revosleap.text.utils.Utils
import com.wafflecopter.multicontactpicker.ContactResult
import com.wafflecopter.multicontactpicker.LimitColumn
import com.wafflecopter.multicontactpicker.MultiContactPicker
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*


class MainActivity : AppCompatActivity(), OnContactClicked, ContactList, MessageClicked {
    private var savedList = mutableListOf<SentMessages>()
    private val pendingContacts = mutableListOf<ContactModel>()
    private var pendingAdapter: SendingAdapter? = null
    private var messagesAdapter: MessagesAdapter? = null
    private var textMessage = ""
    private val box: Box<SentMessages> = Application.boxStore!!.boxFor()
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private var editMode = false
    private var sentMessagesItem: SentMessages? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        setContentView(R.layout.activity_main)
        checkPermissions()
        setGreetings()
        loadHead()
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        setBotttomSheet()
        pendingAdapter = SendingAdapter(this, this)
        messagesAdapter = MessagesAdapter(this)
        compose.setOnClickListener {
            textMessage = message.text.toString().trim()
            if (textMessage.isEmpty()) {
                textInputLayout.error = "Type a message"
            } else {
                chooseContacts()
            }
        }
        loadSavedMessages()
        buttonSend.setOnClickListener(sendClickListener)
        saveDraft.setOnClickListener(draftClickListener)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONTACT_PICKER_RESULT) {
            if (resultCode == Activity.RESULT_OK) {
                val contacts = MultiContactPicker.obtainResult(data)
                showSelectedContacts(contacts)
            } else Toast.makeText(this@MainActivity, "Cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this@MainActivity, "Permissions Must be Allowed", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

    }

    override fun onBackPressed() {
        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        } else super.onBackPressed()
    }

    private fun checkPermissions() {
        val permissionCheck = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CONTACTS)
        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            val c = application.contentResolver.query(ContactsContract.Profile.CONTENT_URI, null, null, null, null)
            c!!.moveToFirst()
            toolbar.subtitle = c.getString(c.getColumnIndex("display_name"))
            c.close()
        } else {
            ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS),
                    PERMISSIONS_REQUEST)
        }
    }

    override fun removeContact(contactModel: ContactModel, position: Int) {
        pendingAdapter!!.removeItem(position)
    }

    override fun contactList(contactList: MutableList<ContactModel>) {
        pendingContacts.clear()
        pendingContacts.addAll(contactList)
    }

    private val draftClickListener = View.OnClickListener {
        val message = message.text.toString().trim()
        if (message.isEmpty()) {
            textInputLayout.error = "Type a message"
        } else {
            textMessage = message
            handleMessage(Utils.DRAFT_STATE, false)
            Utils.toast(this@MainActivity, "Draft Message Saved!!")
        }
    }

    private val sendClickListener = View.OnClickListener {
        if (!editMode) {
            handleMessage(Utils.SENT_STATE, true)
        } else sendEditedDraft()
    }

    private fun handleMessage(state: String, send: Boolean) {
        val smsManager = SmsManager.getDefault()
        val sentMessages = SentMessages()
        val contactsList= mutableListOf<Contacts>()
        sentMessages.message = textMessage
        sentMessages.time = System.currentTimeMillis()
        sentMessages.state = state
        pendingContacts.forEach {
            val contacts = Contacts()
            contacts.contactName = it.name?.trim()
            contacts.phoneNumber = it.phoneNo?.trim()
            sentMessages.contacts.add(contacts)
            contactsList.add(contacts)
            if (send) {
                //TODO Uncomment to send sms
                //smsManager.sendTextMessage(it.phoneNo,null,textMessage,null,null)
            }
        }
        if (!editMode) {
            box.put(sentMessages)
        } else {
            saveEdited(contactsList)
            editMode = false
        }

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        message.setText("")
    }

    override fun onMessageClicked(sentMessages: SentMessages, index: Int) {
        if (sentMessages.state == Utils.DRAFT_STATE) {
            message.setText(sentMessages.message)
            editMode = true
            sentMessagesItem = sentMessages
        } else
            SentRecipients.getInstance(sentMessages).show(supportFragmentManager, "Recipients")
    }

    private fun saveEdited(contactList: MutableList<Contacts>) {
        val queryBuilder = box.query()
        val item = queryBuilder.equal(SentMessages_.id, sentMessagesItem?.id!!).build().find()
        if (item.size > 0) {
            sentMessagesItem?.state = Utils.SENT_STATE
            sentMessagesItem?.contacts= contactList
            box.put(sentMessagesItem!!)
        }
    }

    private fun sendEditedDraft() {
        handleMessage(Utils.SENT_STATE, true)
    }

    private fun chooseContacts() {
        MultiContactPicker.Builder(this@MainActivity)
                .setTitleText("Select Contacts")
                .limitToColumn(LimitColumn.PHONE)
                .setActivityAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out)
                .showPickerForResult(CONTACT_PICKER_RESULT)

    }

    private fun setGreetings() {
        val hr = Calendar.getInstance()
        val currentHour = hr.get(Calendar.HOUR_OF_DAY)
        val greeting: String
        greeting = when {
            currentHour <= 11 -> "Good Morning"
            currentHour <= 15 -> "Good Afternoon"
            currentHour <= 19 -> "Good Evening"
            else -> "Good Evening"
        }
        toolbar.title = greeting
    }


    private fun loadHead() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val image: Bitmap
        image = when {
            hour <= 3 -> getBitmapImageHead(R.drawable.night)
            hour <= 11 -> getBitmapImageHead(R.drawable.morning)
            hour <= 15 -> getBitmapImageHead(R.drawable.afternoon)
            hour <= 21 -> getBitmapImageHead(R.drawable.evening)
            else -> getBitmapImageHead(R.drawable.night)
        }
        imageViewHead.setImageBitmap(image)

    }

    private fun getBitmapImageHead(image: Int): Bitmap {
        val pic = BitmapFactory.decodeResource(resources, image)
        return Blur.blurred(this, pic, 20)
    }

    private fun showSelectedContacts(contacts: ArrayList<ContactResult>) {
        contacts.forEach {
            val model = ContactModel()
            model.name = it.displayName
            model.phoneNo = it.phoneNumbers[0].number
            pendingContacts.add(model)
        }
        pendingAdapter?.addNewItems(pendingContacts)
        recyclerView.apply {
            adapter = pendingAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            hasFixedSize()
        }
        textViewHeader.text = getString(R.string.recipients)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior.peekHeight = 50
        buttonSend.visibility = View.VISIBLE
        setClearButton(1)

    }

    private fun setClearButton(action: Int) {
        buttonClear.setOnClickListener {
            when (action) {
                1 -> {
                    pendingAdapter?.clearAll()
                }
            }
        }

    }

    private fun setBotttomSheet() {
        bottomSheetBehavior.setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(p0: View, p1: Float) {
            }

            override fun onStateChanged(p0: View, p1: Int) {
                if (p1 == BottomSheetBehavior.STATE_EXPANDED) {
                    textViewHeader.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_down, 0, 0)
                } else textViewHeader.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_up, 0, 0)
            }

        })
    }

    private fun loadSavedMessages() {
        savedList = box.all
        savedList.reverse()
        if (savedList.size > 0) {
            recyclerViewMessages.visibility = View.VISIBLE
            include.visibility = View.GONE
        }
        messagesAdapter?.setMessages(savedList)
        recyclerViewMessages.apply {
            hasFixedSize()
            adapter = messagesAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

    }

    fun getStatusBarHeight(): Int {
        var result = 25
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    companion object {
        private const val PERMISSIONS_REQUEST = 100
        private const val CONTACT_PICKER_RESULT = 999
    }


}
