package com.androbuttons.panes.message

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith

class MessagePane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Message"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private companion object {
        const val KEY_MSG_CONTACTS  = "msg_contacts"
        const val KEY_MSG_TEMPLATES = "msg_templates"
    }

    // ---- Data models --------------------------------------------------------

    private data class ContactEntry(val name: String, val typeLabel: String, val number: String)
    private data class MessageTemplate(val text: String)

    // ---- View state ---------------------------------------------------------

    private enum class ViewState { MAIN, CONFIGURE_CONTACTS, CONFIGURE_MESSAGES, SELECT_MESSAGE }
    private var currentView = ViewState.MAIN

    // ---- State --------------------------------------------------------------

    private val contactEntries  = mutableListOf<ContactEntry>()
    private val templates       = mutableListOf<MessageTemplate>()
    private var listIndex       = 0
    private var msgListIndex    = 0
    private var selectedContact: ContactEntry? = null

    private data class ButtonViews(val root: LinearLayout, val nameTv: TextView, val subTv: TextView)
    private data class CardViews(val root: LinearLayout, val textTv: TextView)

    private val buttonViews  = mutableListOf<ButtonViews>()
    private val msgCardViews = mutableListOf<CardViews>()
    private var contactScrollView: ScrollView? = null
    private var msgScrollView: ScrollView? = null
    private var paneRoot: LinearLayout? = null

    // ---- PaneContent --------------------------------------------------------

    override fun buildView(): View {
        val pane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnTouchListener(bridge.makePaneSwipeListener())
        }
        paneRoot = pane
        showMainView()
        return pane
    }

    override fun onResumed() {
        listIndex = 0
        msgListIndex = 0
        selectedContact = null
        currentView = ViewState.MAIN
        showMainView()
    }

    override fun onUp(): Boolean {
        return when (currentView) {
            ViewState.MAIN -> {
                if (buttonViews.isNotEmpty() && listIndex > 0) {
                    listIndex--
                    refreshContactList()
                    scrollContactToSelected()
                }
                true
            }
            ViewState.SELECT_MESSAGE -> {
                if (msgCardViews.isNotEmpty() && msgListIndex > 0) {
                    msgListIndex--
                    refreshMsgList()
                    scrollMsgToSelected()
                }
                true
            }
            else -> false
        }
    }

    override fun onDown(): Boolean {
        return when (currentView) {
            ViewState.MAIN -> {
                if (buttonViews.isNotEmpty() && listIndex < buttonViews.size - 1) {
                    listIndex++
                    refreshContactList()
                    scrollContactToSelected()
                }
                true
            }
            ViewState.SELECT_MESSAGE -> {
                if (msgCardViews.isNotEmpty() && msgListIndex < msgCardViews.size - 1) {
                    msgListIndex++
                    refreshMsgList()
                    scrollMsgToSelected()
                }
                true
            }
            else -> false
        }
    }

    override fun onEnter(): Boolean {
        return when (currentView) {
            ViewState.MAIN -> {
                val entry = contactEntries.getOrNull(listIndex) ?: return true
                openSelectMessage(entry)
                true
            }
            ViewState.SELECT_MESSAGE -> {
                val contact = selectedContact ?: return true
                val tmpl = templates.getOrNull(msgListIndex) ?: return true
                sendSms(contact.number, tmpl.text)
                true
            }
            else -> false
        }
    }

    override fun onCancel(): Boolean {
        return when (currentView) {
            ViewState.SELECT_MESSAGE -> {
                selectedContact = null
                showMainView()
                true
            }
            ViewState.MAIN -> false  // let overlay close
            else -> {
                showMainView()
                true
            }
        }
    }

    // ---- Main view ----------------------------------------------------------

    private fun showMainView() {
        currentView = ViewState.MAIN
        val pane = paneRoot ?: return
        pane.removeAllViews()

        contactEntries.clear()
        contactEntries.addAll(loadContacts())
        templates.clear()
        templates.addAll(loadTemplates())
        listIndex = 0
        buttonViews.clear()

        val buttonList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        contactEntries.forEachIndexed { i, entry ->
            val bv = buildContactButton(entry, isFocused = i == 0)
            buttonViews.add(bv)
            buttonList.addView(bv.root)
        }

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(buttonList)
        }
        contactScrollView = scroll
        pane.addView(scroll)

        pane.addView(makeActionButton("Configure Contacts", Theme.inactiveBg, Theme.textSecondary) {
            showConfigureContactsView()
        })
        pane.addView(makeActionButton("Configure Messages", Theme.inactiveBg, Theme.textSecondary) {
            showConfigureMessagesView()
        })
    }

    // ---- Configure contacts view --------------------------------------------

    private fun showConfigureContactsView() {
        currentView = ViewState.CONFIGURE_CONTACTS
        val pane = paneRoot ?: return
        pane.removeAllViews()

        val allContacts = readAllPhoneContacts()
        val savedKeys = loadContacts().map { contactKey(it) }.toSet()
        val checkedState = mutableMapOf<String, Boolean>()
        allContacts.forEach { checkedState[contactKey(it)] = contactKey(it) in savedKeys }

        fun makeCheckbox(checked: Boolean) = TextView(ctx).apply {
            val size = 24.dp()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = 12.dp() }
            gravity = Gravity.CENTER
            text = if (checked) "✓" else ""
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 4.dp().toFloat()
                if (checked) setColor(Theme.primary)
                else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
            }
        }

        val rowContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        allContacts.forEach { entry ->
            val key = contactKey(entry)
            val checkbox = makeCheckbox(checkedState[key] == true)

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 2.dp() }

                addView(checkbox)

                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = entry.name
                        textSize = 14f
                        setTextColor(Color.WHITE)
                    })
                    addView(TextView(ctx).apply {
                        text = "${entry.typeLabel}: ${entry.number}"
                        textSize = 12f
                        setTextColor(Theme.textSecondary)
                    })
                })

                setOnClickListener {
                    val nowChecked = !(checkedState[key] ?: false)
                    checkedState[key] = nowChecked
                    (checkbox as TextView).text = if (nowChecked) "✓" else ""
                    checkbox.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 4.dp().toFloat()
                        if (nowChecked) setColor(Theme.primary)
                        else { setColor(Color.TRANSPARENT); setStroke(2.dp(), Theme.textSecondary) }
                    }
                }
            }
            rowContainer.addView(row)
        }

        pane.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowContainer)
        })

        pane.addView(makeActionButton("Done", Theme.primary, Color.WHITE) {
            val selected = allContacts.filter { checkedState[contactKey(it)] == true }
            saveContacts(selected)
            showMainView()
        })
    }

    // ---- Configure messages view --------------------------------------------

    private fun showConfigureMessagesView() {
        currentView = ViewState.CONFIGURE_MESSAGES
        val pane = paneRoot ?: return
        pane.removeAllViews()

        val currentTemplates = loadTemplates().toMutableList()
        var editingIndex = -1

        val scrollContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(scrollContainer)
        }

        val inputField = EditText(ctx).apply {
            hint = "New message..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Theme.textSecondary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            setBackgroundColor(Color.parseColor("#FF3D3D3D"))
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
        }

        val addSaveBtn = makeActionButton("Add", Theme.inactiveBg, Color.WHITE) {}

        fun rebuildRows() {
            scrollContainer.removeAllViews()
            currentTemplates.forEachIndexed { idx, tmpl ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 4.dp() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6.dp().toFloat()
                        setColor(Theme.inactiveBg)
                    }

                    addView(TextView(ctx).apply {
                        text = tmpl.text
                        textSize = 13f
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    addView(makeSmallButton("Edit") {
                        editingIndex = idx
                        inputField.setText(currentTemplates[idx].text)
                        inputField.setSelection(inputField.text.length)
                        addSaveBtn.text = "Save"
                    })

                    addView(makeSmallButton("✕") {
                        currentTemplates.removeAt(idx)
                        if (editingIndex == idx) {
                            editingIndex = -1
                            inputField.setText("")
                            addSaveBtn.text = "Add"
                        } else if (editingIndex > idx) {
                            editingIndex--
                        }
                        rebuildRows()
                    })
                }
                scrollContainer.addView(row)
            }
        }

        addSaveBtn.setOnClickListener {
            val txt = inputField.text.toString().trim().replace("\n", " ")
            if (txt.isBlank()) return@setOnClickListener
            if (editingIndex >= 0 && editingIndex < currentTemplates.size) {
                currentTemplates[editingIndex] = MessageTemplate(txt)
                editingIndex = -1
                addSaveBtn.text = "Add"
            } else {
                currentTemplates.add(MessageTemplate(txt))
            }
            inputField.setText("")
            rebuildRows()
        }

        rebuildRows()
        pane.addView(scroll)
        pane.addView(inputField)
        pane.addView(addSaveBtn)
        pane.addView(makeActionButton("Done", Theme.primary, Color.WHITE) {
            saveTemplates(currentTemplates)
            showMainView()
        })
    }

    // ---- Select message view ------------------------------------------------

    private fun openSelectMessage(contact: ContactEntry) {
        currentView = ViewState.SELECT_MESSAGE
        selectedContact = contact
        msgListIndex = 0
        val pane = paneRoot ?: return
        pane.removeAllViews()
        msgCardViews.clear()

        pane.addView(TextView(ctx).apply {
            text = "Send to: ${contact.name}"
            textSize = 14f
            setTextColor(Theme.textSecondary)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        })

        val cardList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        templates.forEachIndexed { i, tmpl ->
            val cv = buildMsgCard(tmpl, isFocused = i == 0)
            msgCardViews.add(cv)
            cardList.addView(cv.root)
        }

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(cardList)
        }
        msgScrollView = scroll
        pane.addView(scroll)

        pane.addView(makeActionButton("Cancel", Theme.inactiveBg, Theme.textSecondary) {
            selectedContact = null
            showMainView()
        })
    }

    // ---- Contact button -----------------------------------------------------

    private fun buildContactButton(entry: ContactEntry, isFocused: Boolean): ButtonViews {
        val nameTv = TextView(ctx).apply {
            text = entry.name
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val subTv = TextView(ctx).apply {
            text = "${entry.typeLabel}: ${entry.number}"
            textSize = 13f
            setTextColor(Theme.textSecondary)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = buttonBg(isFocused, ctx)
            isClickable = true
            addView(nameTv)
            addView(subTv)
            setOnClickListener {
                val idx = buttonViews.indexOfFirst { it.root === this }
                if (idx < 0) return@setOnClickListener
                listIndex = idx
                refreshContactList()
                openSelectMessage(contactEntries[idx])
            }
        }
        return ButtonViews(root, nameTv, subTv)
    }

    // ---- Message card -------------------------------------------------------

    private fun buildMsgCard(tmpl: MessageTemplate, isFocused: Boolean): CardViews {
        val textTv = TextView(ctx).apply {
            text = tmpl.text
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
            background = buttonBg(isFocused, ctx)
            isClickable = true
            addView(textTv)
            setOnClickListener {
                val idx = msgCardViews.indexOfFirst { it.root === this }
                if (idx < 0) return@setOnClickListener
                val contact = selectedContact ?: return@setOnClickListener
                sendSms(contact.number, templates[idx].text)
            }
        }
        return CardViews(root, textTv)
    }

    // ---- List helpers -------------------------------------------------------

    private fun refreshContactList() {
        buttonViews.forEachIndexed { i, bv ->
            bv.root.background = buttonBg(i == listIndex, ctx)
        }
    }

    private fun scrollContactToSelected() {
        val scroll = contactScrollView ?: return
        val btn = buttonViews.getOrNull(listIndex)?.root ?: return
        scroll.post {
            val top = btn.top; val bottom = btn.bottom
            val scrollY = scroll.scrollY; val visible = scroll.height
            if (top < scrollY) scroll.smoothScrollTo(0, top)
            else if (bottom > scrollY + visible) scroll.smoothScrollTo(0, bottom - visible)
        }
    }

    private fun refreshMsgList() {
        msgCardViews.forEachIndexed { i, cv ->
            cv.root.background = buttonBg(i == msgListIndex, ctx)
        }
    }

    private fun scrollMsgToSelected() {
        val scroll = msgScrollView ?: return
        val card = msgCardViews.getOrNull(msgListIndex)?.root ?: return
        scroll.post {
            val top = card.top; val bottom = card.bottom
            val scrollY = scroll.scrollY; val visible = scroll.height
            if (top < scrollY) scroll.smoothScrollTo(0, top)
            else if (bottom > scrollY + visible) scroll.smoothScrollTo(0, bottom - visible)
        }
    }

    // ---- SMS ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun sendSms(number: String, text: String) {
        try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
        } catch (_: Exception) { }
        bridge.hideOverlay()
    }

    // ---- Contacts query -----------------------------------------------------

    private fun readAllPhoneContacts(): List<ContactEntry> {
        val results = mutableListOf<ContactEntry>()
        try {
            val cursor = ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            ) ?: return results

            cursor.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val lblIdx  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)
                while (it.moveToNext()) {
                    val name      = it.getString(nameIdx) ?: continue
                    val number    = it.getString(numIdx)?.trim() ?: continue
                    val type      = it.getInt(typeIdx)
                    val label     = if (lblIdx >= 0) it.getString(lblIdx) else null
                    val typeLabel = ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(ctx.resources, type, label).toString()
                    results.add(ContactEntry(name, typeLabel, number))
                }
            }
        } catch (_: Exception) { }
        return results
    }

    // ---- Prefs helpers ------------------------------------------------------

    private fun contactKey(e: ContactEntry) = "${e.name}|${e.typeLabel}|${e.number}"

    private fun loadContacts(): List<ContactEntry> {
        val raw = bridge.getStringPref(KEY_MSG_CONTACTS, null) ?: return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 3) ContactEntry(parts[0], parts[1], parts.drop(2).joinToString("|"))
            else null
        }
    }

    private fun saveContacts(contacts: List<ContactEntry>) {
        bridge.putStringPref(KEY_MSG_CONTACTS, contacts.joinToString("\n") { contactKey(it) })
    }

    private fun loadTemplates(): List<MessageTemplate> {
        val raw = bridge.getStringPref(KEY_MSG_TEMPLATES, null) ?: return emptyList()
        return raw.lines().filter { it.isNotBlank() }.map { MessageTemplate(it) }
    }

    private fun saveTemplates(tmplList: List<MessageTemplate>) {
        bridge.putStringPref(KEY_MSG_TEMPLATES, tmplList.joinToString("\n") { it.text })
    }

    // ---- UI helpers ---------------------------------------------------------

    private fun makeActionButton(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = actionButtonBg(bgColor, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallButton(label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = actionButtonBg(Theme.inactiveBg, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 6.dp() }
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}
