package com.androbuttons.panes.phone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.ContactsContract
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.androbuttons.common.PaneContent
import com.androbuttons.common.ServiceBridge
import com.androbuttons.common.Theme
import com.androbuttons.common.actionButtonBg
import com.androbuttons.common.buttonBg
import com.androbuttons.common.dpWith

class PhonePane(private val bridge: ServiceBridge) : PaneContent {

    override val title = "Phone"

    private val ctx: Context get() = bridge.context
    private fun Int.dp() = dpWith(ctx)

    private companion object {
        const val KEY_PHONE_CONTACTS = "phone_contacts"
    }

    // ---- Data model ---------------------------------------------------------

    private data class ContactEntry(val name: String, val typeLabel: String, val number: String)

    // ---- State --------------------------------------------------------------

    private val contactEntries = mutableListOf<ContactEntry>()
    private var listIndex = 0
    private var confirmingIndex = -1

    // Per-button view references for in-place confirm text update
    private data class ButtonViews(val root: LinearLayout, val nameTv: TextView, val subTv: TextView)

    private val buttonViews = mutableListOf<ButtonViews>()
    private var contactScrollView: ScrollView? = null
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
        showPhoneView()
        return pane
    }

    override fun onResumed() {
        listIndex = 0
        confirmingIndex = -1
        refreshList()
    }

    override fun onUp(): Boolean {
        if (buttonViews.isNotEmpty() && listIndex > 0) {
            resetConfirm()
            listIndex--
            refreshList()
            scrollToSelected()
        }
        return true
    }

    override fun onDown(): Boolean {
        if (buttonViews.isNotEmpty() && listIndex < buttonViews.size - 1) {
            resetConfirm()
            listIndex++
            refreshList()
            scrollToSelected()
        }
        return true
    }

    override fun onEnter(): Boolean {
        if (buttonViews.isEmpty()) return true
        if (confirmingIndex == listIndex) {
            // Second press → initiate call
            val entry = contactEntries.getOrNull(listIndex) ?: return true
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${entry.number}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            bridge.hideOverlay()
            resetConfirm()
        } else {
            // First press → show confirmation prompt on button
            resetConfirm()
            confirmingIndex = listIndex
            updateButtonForConfirm(listIndex, confirming = true)
        }
        return true
    }

    override fun onCancel(): Boolean {
        if (confirmingIndex >= 0) {
            resetConfirm()
            return true  // absorb cancel, keep overlay open
        }
        return false     // let overlay close
    }

    // ---- Phone view ---------------------------------------------------------

    private fun showPhoneView() {
        val pane = paneRoot ?: return
        pane.removeAllViews()
        confirmingIndex = -1

        contactEntries.clear()
        contactEntries.addAll(loadContacts())
        listIndex = 0
        buttonViews.clear()

        val buttonList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        contactEntries.forEachIndexed { i, entry ->
            val bv = buildContactButton(entry, isFocused = i == 0)
            buttonViews.add(bv)
            buttonList.addView(bv.root)
        }

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(buttonList)
        }
        contactScrollView = scroll
        pane.addView(scroll)

        pane.addView(TextView(ctx).apply {
            text = "Configure"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Theme.textSecondary)
            background = actionButtonBg(Theme.inactiveBg, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener { showConfigureView() }
        })
    }

    // ---- Configure view -----------------------------------------------------

    private fun showConfigureView() {
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

                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
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
                }
                addView(textCol)

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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(rowContainer)
        })

        pane.addView(TextView(ctx).apply {
            text = "Done"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = actionButtonBg(Theme.primary, ctx)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp() }
            setPadding(12.dp(), 18.dp(), 12.dp(), 18.dp())
            isClickable = true
            setOnClickListener {
                val selected = allContacts.filter { checkedState[contactKey(it)] == true }
                saveContacts(selected)
                showPhoneView()
            }
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
                if (confirmingIndex == idx) {
                    val e = contactEntries.getOrNull(idx) ?: return@setOnClickListener
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${e.number}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    bridge.hideOverlay()
                    resetConfirm()
                } else {
                    listIndex = idx
                    resetConfirm()
                    confirmingIndex = idx
                    refreshList()
                    updateButtonForConfirm(idx, confirming = true)
                }
            }
        }
        return ButtonViews(root, nameTv, subTv)
    }

    // ---- List helpers -------------------------------------------------------

    private fun refreshList() {
        buttonViews.forEachIndexed { i, bv ->
            bv.root.background = buttonBg(i == listIndex, ctx)
        }
    }

    private fun scrollToSelected() {
        val scroll = contactScrollView ?: return
        val btn = buttonViews.getOrNull(listIndex)?.root ?: return
        scroll.post {
            val top = btn.top; val bottom = btn.bottom
            val scrollY = scroll.scrollY; val visible = scroll.height
            if (top < scrollY) scroll.smoothScrollTo(0, top)
            else if (bottom > scrollY + visible) scroll.smoothScrollTo(0, bottom - visible)
        }
    }

    private fun updateButtonForConfirm(index: Int, confirming: Boolean) {
        val bv = buttonViews.getOrNull(index) ?: return
        val entry = contactEntries.getOrNull(index) ?: return
        if (confirming) {
            bv.nameTv.text = "Call ${entry.name}?"
            bv.nameTv.setTextColor(Theme.primary)
            bv.subTv.visibility = View.GONE
        } else {
            bv.nameTv.text = entry.name
            bv.nameTv.setTextColor(Color.WHITE)
            bv.subTv.visibility = View.VISIBLE
        }
    }

    private fun resetConfirm() {
        val prev = confirmingIndex
        confirmingIndex = -1
        if (prev >= 0) updateButtonForConfirm(prev, confirming = false)
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
                    val name   = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx)?.trim() ?: continue
                    val type   = it.getInt(typeIdx)
                    val label  = if (lblIdx >= 0) it.getString(lblIdx) else null
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
        val raw = bridge.getStringPref(KEY_PHONE_CONTACTS, null) ?: return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 3) ContactEntry(parts[0], parts[1], parts.drop(2).joinToString("|"))
            else null
        }
    }

    private fun saveContacts(contacts: List<ContactEntry>) {
        bridge.putStringPref(KEY_PHONE_CONTACTS, contacts.joinToString("\n") { contactKey(it) })
    }
}
