/*
 * Copyright (C) 2016 Automattic
 * Copyright (C) 2015 Matthew Lee
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wordpress.aztec

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.text.*
import android.text.style.ParagraphStyle
import android.text.style.SuggestionSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import org.wordpress.aztec.formatting.BlockFormatter
import org.wordpress.aztec.formatting.InlineFormatter
import org.wordpress.aztec.formatting.LineBlockFormatter
import org.wordpress.aztec.formatting.LinkFormatter
import org.wordpress.aztec.handlers.HeadingHandler
import org.wordpress.aztec.handlers.ListHandler
import org.wordpress.aztec.handlers.ListItemHandler
import org.wordpress.aztec.handlers.QuoteHandler
import org.wordpress.aztec.source.Format
import org.wordpress.aztec.source.SourceViewEditText
import org.wordpress.aztec.spans.*
import org.wordpress.aztec.toolbar.AztecToolbar
import org.wordpress.aztec.watchers.*
import org.xml.sax.Attributes
import java.util.*

class AztecText : EditText, TextWatcher, UnknownHtmlSpan.OnUnknownHtmlClickListener {

    companion object {
        val BLOCK_EDITOR_HTML_KEY = "RETAINED_BLOCK_HTML_KEY"
        val BLOCK_EDITOR_START_INDEX_KEY = "BLOCK_EDITOR_START_INDEX_KEY"
        val BLOCK_DIALOG_VISIBLE_KEY = "BLOCK_DIALOG_VISIBLE_KEY"

        val LINK_DIALOG_VISIBLE_KEY = "LINK_DIALOG_VISIBLE_KEY"
        val LINK_DIALOG_URL_KEY = "LINK_DIALOG_URL_KEY"
        val LINK_DIALOG_ANCHOR_KEY = "LINK_DIALOG_ANCHOR_KEY"

        val HISTORY_LIST_KEY = "HISTORY_LIST_KEY"
        val HISTORY_CURSOR_KEY = "HISTORY_CURSOR_KEY"

        val SELECTION_START_KEY = "SELECTION_START_KEY"
        val SELECTION_END_KEY = "SELECTION_END_KEY"

        val INPUT_LAST_KEY = "INPUT_LAST_KEY"
        val VISIBILITY_KEY = "VISIBILITY_KEY"
        val IS_MEDIA_ADDED_KEY = "IS_MEDIA_ADDED_KEY"
        val RETAINED_HTML_KEY = "RETAINED_HTML_KEY"
    }

    private var historyEnable = resources.getBoolean(R.bool.history_enable)
    private var historySize = resources.getInteger(R.integer.history_size)

    private var addLinkDialog: AlertDialog? = null
    private var blockEditorDialog: AlertDialog? = null
    private var consumeEditEvent: Boolean = false

    private var onSelectionChangedListener: OnSelectionChangedListener? = null
    private var onImeBackListener: OnImeBackListener? = null
    private var onMediaTappedListener: OnMediaTappedListener? = null

    private var isViewInitialized = false
    private var previousCursorPosition = 0

    private var unknownBlockSpanStart = -1

    private var formatToolbar: AztecToolbar? = null

    val selectedStyles = ArrayList<TextFormat>()

    private var isNewStyleSelected = false

    private var drawableFailed: Int = 0
    private var drawableLoading: Int = 0

    var isMediaAdded = false

    lateinit var history: History

    lateinit var inlineFormatter: InlineFormatter
    lateinit var blockFormatter: BlockFormatter
    lateinit var lineBlockFormatter: LineBlockFormatter
    lateinit var linkFormatter: LinkFormatter

    var imageGetter: Html.ImageGetter? = null

    var widthMeasureSpec: Int = 0

    interface OnSelectionChangedListener {
        fun onSelectionChanged(selStart: Int, selEnd: Int)
    }

    interface OnImeBackListener {
        fun onImeBack()
    }

    interface OnMediaTappedListener {
        fun mediaTapped(attrs: Attributes?, naturalWidth: Int, naturalHeight: Int)
    }

    constructor(context: Context) : super(context) {
        init(null)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(attrs)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(attrs)
    }

    private fun init(attrs: AttributeSet?) {
        disableTextChangedListener()

        val styles = context.obtainStyledAttributes(attrs, R.styleable.AztecText, 0, R.style.AztecTextStyle)
        setLineSpacing(
                styles.getDimension(
                        R.styleable.AztecText_lineSpacingExtra,
                        resources.getDimension(R.dimen.spacing_extra)
                ),
                styles.getFloat(
                        R.styleable.AztecText_lineSpacingMultiplier,
                        resources.getString(R.dimen.spacing_multiplier).toFloat()
                )
        )
        setBackgroundColor(styles.getColor(R.styleable.AztecText_backgroundColor, ContextCompat.getColor(context, R.color.background)))
        setTextColor(styles.getColor(R.styleable.AztecText_textColor, ContextCompat.getColor(context, R.color.text)))
        setHintTextColor(styles.getColor(R.styleable.AztecText_textColorHint, ContextCompat.getColor(context, R.color.text_hint)))

        drawableLoading = styles.getResourceId(R.styleable.AztecText_drawableLoading, R.drawable.ic_image_loading)
        drawableFailed = styles.getResourceId(R.styleable.AztecText_drawableFailed, R.drawable.ic_image_failed)

        historyEnable = styles.getBoolean(R.styleable.AztecText_historyEnable, historyEnable)
        historySize = styles.getInt(R.styleable.AztecText_historySize, historySize)

        inlineFormatter = InlineFormatter(this,
                InlineFormatter.CodeStyle(
                        styles.getColor(R.styleable.AztecText_codeBackground, 0),
                        styles.getFraction(R.styleable.AztecText_codeBackgroundAlpha, 1, 1, 0f),
                        styles.getColor(R.styleable.AztecText_codeColor, 0)))

        blockFormatter = BlockFormatter(this,
                BlockFormatter.ListStyle(
                        styles.getColor(R.styleable.AztecText_bulletColor, 0),
                        styles.getDimensionPixelSize(R.styleable.AztecText_bulletMargin, 0),
                        styles.getDimensionPixelSize(R.styleable.AztecText_bulletPadding, 0),
                        styles.getDimensionPixelSize(R.styleable.AztecText_bulletWidth, 0),
                        styles.getDimensionPixelSize(R.styleable.AztecText_blockVerticalPadding, 0)),
                BlockFormatter.QuoteStyle(
                        styles.getColor(R.styleable.AztecText_quoteBackground, 0),
                        styles.getColor(R.styleable.AztecText_quoteColor, 0),
                        styles.getFraction(R.styleable.AztecText_quoteBackgroundAlpha, 1, 1, 0f),
                        styles.getDimensionPixelSize(R.styleable.AztecText_quoteMargin, 0),
                        styles.getDimensionPixelSize(R.styleable.AztecText_quotePadding, 0),
                        styles.getDimensionPixelSize(R.styleable.AztecText_quoteWidth, 0),
                       styles.getDimensionPixelSize(R.styleable.AztecText_blockVerticalPadding, 0)),
                BlockFormatter.HeaderStyle(
                        styles.getDimensionPixelSize(R.styleable.AztecText_blockVerticalPadding, 0))
                )

        linkFormatter = LinkFormatter(this, LinkFormatter.LinkStyle(styles.getColor(
                R.styleable.AztecText_linkColor, 0),
                styles.getBoolean(R.styleable.AztecText_linkUnderline, true)))

        lineBlockFormatter = LineBlockFormatter(this)

        styles.recycle()

        if (historyEnable && historySize <= 0) {
            throw IllegalArgumentException("historySize must > 0")
        }

        history = History(historyEnable, historySize)

        // triggers ClickableSpan onClick() events
        movementMethod = EnhancedMovementMethod

        //Detect the press of backspace from hardware keyboard when no characters are deleted (eg. at 0 index of EditText)
        setOnKeyListener { v, keyCode, event ->
            var consumeKeyEvent = false
            history.beforeTextChanged(toFormattedHtml())
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                inlineFormatter.tryRemoveLeadingInlineStyle()
                consumeKeyEvent = blockFormatter.tryRemoveBlockStyleFromFirstLine()
            }

            if (consumeKeyEvent) {
                history.handleHistory(this@AztecText)
            }
            consumeKeyEvent
        }

        install()

        enableTextChangedListener()

        isViewInitialized = true
    }

    private fun install() {
        ParagraphBleedAdjuster.install(this)
        ParagraphCollapseAdjuster.install(this)
        ParagraphCollapseRemover.install(this)

        InlineTextWatcher.install(inlineFormatter, this)

        // NB: text change handler should not alter text before "afterTextChanged" is called otherwise not all watchers
        //  will have the chance to run their "beforeTextChanged" and "onTextChanged" with the same string!

        BlockElementWatcher(this)
                .add(HeadingHandler())
                .add(ListHandler())
                .add(ListItemHandler())
                .add(QuoteHandler())
                .install(this)

        TextDeleter.install(this)

        FullWidthImageElementWatcher.install(this)

        EndOfBufferMarkerAdder.install(this)

    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        val selStart = selectionStart
        val selEnd = selectionEnd

        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            //on older android versions selection is lost when window loses focus, so we are making sure to keep it
            setSelection(selStart, selEnd)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        addTextChangedListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeTextChangedListener(this)
        if (addLinkDialog != null && addLinkDialog!!.isShowing) {
            addLinkDialog!!.dismiss()
        }

        if (blockEditorDialog != null && blockEditorDialog!!.isShowing) {
            blockEditorDialog!!.dismiss()
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        disableTextChangedListener()

        val savedState = state as SavedState
        super.onRestoreInstanceState(savedState.superState)
        val customState = savedState.state
        val array = ArrayList(customState.getStringArrayList(HISTORY_LIST_KEY))
        val list = LinkedList<String>()

        list += array

        history.historyList = list
        history.historyCursor = customState.getInt(HISTORY_CURSOR_KEY)
        history.inputLast = customState.getString(INPUT_LAST_KEY)
        visibility = customState.getInt(VISIBILITY_KEY)

        val retainedHtml = customState.getString(RETAINED_HTML_KEY)
        fromHtml(retainedHtml)

        val retainedSelectionStart = customState.getInt(SELECTION_START_KEY)
        val retainedSelectionEnd = customState.getInt(SELECTION_END_KEY)

        if (retainedSelectionEnd < editableText.length) {
            setSelection(retainedSelectionStart, retainedSelectionEnd)
        }


        val isLinkDialogVisible = customState.getBoolean(LINK_DIALOG_VISIBLE_KEY, false)
        if (isLinkDialogVisible) {
            val retainedUrl = customState.getString(LINK_DIALOG_URL_KEY, "")
            val retainedAnchor = customState.getString(LINK_DIALOG_ANCHOR_KEY, "")

            showLinkDialog(retainedUrl, retainedAnchor)
        }

        val isBlockEditorDialogVisible = customState.getBoolean(BLOCK_DIALOG_VISIBLE_KEY, false)
        if (isBlockEditorDialogVisible) {

            val retainedBlockHtmlIndex = customState.getInt(BLOCK_EDITOR_START_INDEX_KEY, -1)
            if (retainedBlockHtmlIndex != -1) {

                val unknownSpan = text.getSpans(retainedBlockHtmlIndex, retainedBlockHtmlIndex + 1, UnknownHtmlSpan::class.java).firstOrNull()
                if (unknownSpan != null) {

                    val retainedBlockHtml = customState.getString(BLOCK_EDITOR_HTML_KEY)
                    showBlockEditorDialog(unknownSpan, retainedBlockHtml)
                }
            }
        }

        isMediaAdded = customState.getBoolean(IS_MEDIA_ADDED_KEY)

        enableTextChangedListener()
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val savedState = SavedState(superState)
        val bundle = Bundle()
        bundle.putStringArrayList(HISTORY_LIST_KEY, ArrayList<String>(history.historyList))
        bundle.putInt(HISTORY_CURSOR_KEY, history.historyCursor)
        bundle.putString(INPUT_LAST_KEY, history.inputLast)
        bundle.putInt(VISIBILITY_KEY, visibility)
        bundle.putString(RETAINED_HTML_KEY, toHtml(false))
        bundle.putInt(SELECTION_START_KEY, selectionStart)
        bundle.putInt(SELECTION_END_KEY, selectionEnd)

        if (addLinkDialog != null && addLinkDialog!!.isShowing) {
            bundle.putBoolean(LINK_DIALOG_VISIBLE_KEY, true)

            val urlInput = addLinkDialog!!.findViewById(R.id.linkURL) as EditText
            val anchorInput = addLinkDialog!!.findViewById(R.id.linkText) as EditText

            bundle.putString(LINK_DIALOG_URL_KEY, urlInput.text.toString())
            bundle.putString(LINK_DIALOG_ANCHOR_KEY, anchorInput.text.toString())
        }

        if (blockEditorDialog != null && blockEditorDialog!!.isShowing) {
            val source = blockEditorDialog!!.findViewById(R.id.source) as SourceViewEditText

            bundle.putBoolean(BLOCK_DIALOG_VISIBLE_KEY, true)
            bundle.putInt(BLOCK_EDITOR_START_INDEX_KEY, unknownBlockSpanStart)
            bundle.putString(BLOCK_EDITOR_HTML_KEY, source.getPureHtml(false))
        }

        bundle.putBoolean(IS_MEDIA_ADDED_KEY, isMediaAdded)

        savedState.state = bundle
        return savedState
    }

    internal class SavedState : BaseSavedState {
        var state: Bundle = Bundle()

        constructor(superState: Parcelable) : super(superState)

        constructor(parcel: Parcel) : super(parcel) {
            state = parcel.readBundle(javaClass.classLoader)
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeBundle(state)
        }


        companion object {
            @JvmField val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState {
                    return SavedState(source)
                }

                override fun newArray(size: Int): Array<SavedState?> {
                    return arrayOfNulls(size)
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // capture the width spec to be used when pre-layingout AztecMediaSpans
        this.widthMeasureSpec = widthMeasureSpec

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setSelectedStyles(styles: ArrayList<TextFormat>) {
        isNewStyleSelected = true
        selectedStyles.clear()
        selectedStyles.addAll(styles)
    }

    fun setOnSelectionChangedListener(onSelectionChangedListener: OnSelectionChangedListener) {
        this.onSelectionChangedListener = onSelectionChangedListener
    }

    fun setOnImeBackListener(listener: OnImeBackListener) {
        this.onImeBackListener = listener
    }

    fun setOnMediaTappedListener(listener: OnMediaTappedListener) {
        this.onMediaTappedListener = listener
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onImeBackListener?.onImeBack()
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean {
        if (formatToolbar?.onKeyUp(keyCode, keyEvent) ?: false) {
            return true
        } else {
            return super.onKeyUp(keyCode, keyEvent)
        }
    }

    public override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!isViewInitialized) return

        if (length() != 0) {
            // if the text end has the marker, let's make sure the cursor never includes it or surpusses it
            if ((selStart == length() || selEnd == length()) && text[length() - 1] == Constants.END_OF_BUFFER_MARKER) {
                var start = selStart
                var end = selEnd

                if (start == length()) {
                    start--
                }

                if (end == length()) {
                    end--
                }

                setSelection(start, end)
                return
            }
        }

        previousCursorPosition = selEnd

        onSelectionChangedListener?.onSelectionChanged(selStart, selEnd)

        setSelectedStyles(getAppliedStyles(selStart, selEnd))
    }

    fun getSelectedText(): String {
        if (selectionStart == -1 || selectionEnd == -1
                || editableText.length < selectionEnd || editableText.length < selectionStart) return ""
        return editableText.substring(selectionStart, selectionEnd)
    }

    fun getAppliedStyles(selectionStart: Int, selectionEnd: Int): ArrayList<TextFormat> {
        val styles = ArrayList<TextFormat>()

        var newSelStart = if (selectionStart > selectionEnd) selectionEnd else selectionStart
        var newSelEnd = selectionEnd

        if (editableText.isEmpty()) {
            return styles
        }

        if (newSelStart == 0 && newSelEnd == 0) {
            newSelEnd++
        } else if (newSelStart == newSelEnd && editableText.length > selectionStart && editableText[selectionStart - 1] == Constants.NEWLINE) {
            newSelEnd++
        } else if (newSelStart > 0 && !isTextSelected()) {
            newSelStart--
        }


        TextFormat.values().forEach {
            if (contains(it, newSelStart, newSelEnd)) {
                styles.add(it)
            }
        }
        return styles
    }

    fun isEmpty(): Boolean {
        return text.isEmpty()
    }

    fun formattingIsApplied(): Boolean {
        return !selectedStyles.isEmpty()
    }

    fun formattingHasChanged(): Boolean {
        return isNewStyleSelected
    }

    fun setFormattingChangesApplied() {
        isNewStyleSelected = false
    }

    fun isTextSelected(): Boolean {
        return selectionStart != selectionEnd
    }

    fun toggleFormatting(textFormat: TextFormat) {
        history.beforeTextChanged(toFormattedHtml())

        when (textFormat) {
            TextFormat.FORMAT_PARAGRAPH,
            TextFormat.FORMAT_HEADING_1,
            TextFormat.FORMAT_HEADING_2,
            TextFormat.FORMAT_HEADING_3,
            TextFormat.FORMAT_HEADING_4,
            TextFormat.FORMAT_HEADING_5,
            TextFormat.FORMAT_HEADING_6 -> blockFormatter.toggleHeading(textFormat)
            TextFormat.FORMAT_BOLD -> inlineFormatter.toggleBold()
            TextFormat.FORMAT_ITALIC -> inlineFormatter.toggleItalic()
            TextFormat.FORMAT_UNDERLINE -> inlineFormatter.toggleUnderline()
            TextFormat.FORMAT_STRIKETHROUGH -> inlineFormatter.toggleStrikethrough()
            TextFormat.FORMAT_UNORDERED_LIST -> blockFormatter.toggleUnorderedList()
            TextFormat.FORMAT_ORDERED_LIST -> blockFormatter.toggleOrderedList()
            TextFormat.FORMAT_QUOTE -> blockFormatter.toggleQuote()
            TextFormat.FORMAT_MORE -> lineBlockFormatter.applyMoreComment()
            TextFormat.FORMAT_PAGE -> lineBlockFormatter.applyPageComment()
            TextFormat.FORMAT_CODE -> inlineFormatter.toggleCode()
            else -> {
            }
        }

        history.handleHistory(this)
    }

    fun contains(format: TextFormat, selStart: Int = selectionStart, selEnd: Int = selectionEnd): Boolean {
        when (format) {
            TextFormat.FORMAT_HEADING_1,
            TextFormat.FORMAT_HEADING_2,
            TextFormat.FORMAT_HEADING_3,
            TextFormat.FORMAT_HEADING_4,
            TextFormat.FORMAT_HEADING_5,
            TextFormat.FORMAT_HEADING_6 -> return lineBlockFormatter.containsHeading(format, selStart, selEnd)
            TextFormat.FORMAT_BOLD -> return inlineFormatter.containsInlineStyle(TextFormat.FORMAT_BOLD, selStart, selEnd)
            TextFormat.FORMAT_ITALIC -> return inlineFormatter.containsInlineStyle(TextFormat.FORMAT_ITALIC, selStart, selEnd)
            TextFormat.FORMAT_UNDERLINE -> return inlineFormatter.containsInlineStyle(TextFormat.FORMAT_UNDERLINE, selStart, selEnd)
            TextFormat.FORMAT_STRIKETHROUGH -> return inlineFormatter.containsInlineStyle(TextFormat.FORMAT_STRIKETHROUGH, selStart, selEnd)
            TextFormat.FORMAT_UNORDERED_LIST -> return blockFormatter.containsList(TextFormat.FORMAT_UNORDERED_LIST, selStart, selEnd)
            TextFormat.FORMAT_ORDERED_LIST -> return blockFormatter.containsList(TextFormat.FORMAT_ORDERED_LIST, selStart, selEnd)
            TextFormat.FORMAT_QUOTE -> return blockFormatter.containQuote(selectionStart, selectionEnd)
            TextFormat.FORMAT_LINK -> return linkFormatter.containLink(selStart, selEnd)
            TextFormat.FORMAT_CODE -> return inlineFormatter.containsInlineStyle(TextFormat.FORMAT_CODE, selStart, selEnd)
            else -> return false
        }
    }

    fun setToolbar(toolbar: AztecToolbar) {
        formatToolbar = toolbar
    }

    override fun beforeTextChanged(text: CharSequence, start: Int, count: Int, after: Int) {
        if (!isViewInitialized) return

        if (!isTextChangedListenerDisabled()) {
            history.beforeTextChanged(toFormattedHtml())
        }
    }

    override fun onTextChanged(text: CharSequence, start: Int, before: Int, count: Int) {
        if (!isViewInitialized) return
    }

    override fun afterTextChanged(text: Editable) {
        if (isTextChangedListenerDisabled()) {
            return
        }

        isMediaAdded = text.getSpans(0, text.length, AztecMediaSpan::class.java).isNotEmpty()

        history.handleHistory(this)
    }

    fun redo() {
        history.redo(this)
    }

    fun undo() {
        history.undo(this)
    }

    // Helper ======================================================================================

    fun consumeCursorPosition(text: SpannableStringBuilder): Int {
        var cursorPosition = Math.min(selectionStart, length())

        text.getSpans(0, text.length, AztecCursorSpan::class.java).forEach {
            cursorPosition = text.getSpanStart(it)
            text.removeSpan(it)
        }

        return cursorPosition
    }

    fun fromHtml(source: String) {
        val builder = SpannableStringBuilder()
        val parser = AztecParser()
        builder.append(parser.fromHtml(source, onMediaTappedListener, this, context))

        switchToAztecStyle(builder, 0, builder.length)
        disableTextChangedListener()

        builder.getSpans(0, builder.length, AztecDynamicImageSpan::class.java).forEach {
            it.textView = this
        }

        setTextKeepState(builder)
        enableTextChangedListener()

        val cursorPosition = consumeCursorPosition(builder)
        setSelection(cursorPosition)

        loadImages()
    }

    private fun loadImages() {
        val spans = this.text.getSpans(0, text.length, AztecMediaSpan::class.java)
        spans.forEach {
            val callbacks = object : Html.ImageGetter.Callbacks {

                override fun onImageFailed() {
                    replaceImage(ContextCompat.getDrawable(context, drawableFailed))
                }

                override fun onImageLoaded(drawable: Drawable?) {
                    replaceImage(drawable)
                }

                override fun onImageLoading(drawable: Drawable?) {
                    replaceImage(ContextCompat.getDrawable(context, drawableLoading))
                }

                override fun onUseDefaultImage() {
                    // we already have a default image loaded so, noop
                }

                private fun replaceImage(drawable: Drawable?) {
                    it.drawable = drawable
                    post {
                        refreshText()
                    }
                }
            }

            // maxidth set to the biggest of screen width/height to cater for device rotation
            val maxWidth = Math.max(context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels)
            imageGetter?.loadImage(it.getSource(), callbacks, maxWidth)
        }
    }

    fun toHtml(withCursorTag: Boolean = false): String {
        val parser = AztecParser()
        val output = SpannableStringBuilder(text)

        clearMetaSpans(output)

        for (span in output.getSpans(0, output.length, AztecCursorSpan::class.java)) {
            output.removeSpan(span)
        }
        if (withCursorTag) {
            output.setSpan(AztecCursorSpan(), selectionEnd, selectionEnd, Spanned.SPAN_MARK_MARK)
        }

        parser.syncVisualNewlinesOfBlockElements(output)
        val html = parser.toHtml(output, withCursorTag)

        return EndOfBufferMarkerAdder.removeEndOfTextMarker(html)
    }

    fun toFormattedHtml(): String {
        return Format.addSourceEditorFormatting(toHtml())
    }

    private fun switchToAztecStyle(editable: Editable, start: Int, end: Int) {
        editable.getSpans(start, end, AztecBlockSpan::class.java).forEach { blockFormatter.setBlockStyle(it) }

        val urlSpans = editable.getSpans(start, end, AztecURLSpan::class.java)
        for (span in urlSpans) {
            val spanStart = editable.getSpanStart(span)
            val spanEnd = editable.getSpanEnd(span)
            editable.removeSpan(span)
            editable.setSpan(linkFormatter.makeUrlSpan(span.url, span.attributes), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val codeSpans = editable.getSpans(start, end, AztecCodeSpan::class.java)
        codeSpans.forEach {
            val spanStart = editable.getSpanStart(it)
            val spanEnd = editable.getSpanEnd(it)
            editable.removeSpan(it)
            editable.setSpan(inlineFormatter.makeInlineSpan(it.javaClass, it.attributes), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun disableTextChangedListener() {
        consumeEditEvent = true
    }

    fun enableTextChangedListener() {
        consumeEditEvent = false
    }

    fun isTextChangedListenerDisabled(): Boolean {
        return consumeEditEvent
    }

    fun refreshText() {
        disableTextChangedListener()
        val selStart = selectionStart
        val selEnd = selectionEnd
        text = editableText
        setSelection(selStart, selEnd)
        enableTextChangedListener()
    }

    fun removeInlineStylesFromRange(start: Int, end: Int) {
        inlineFormatter.removeInlineStyle(TextFormat.FORMAT_BOLD, start, end)
        inlineFormatter.removeInlineStyle(TextFormat.FORMAT_ITALIC, start, end)
        inlineFormatter.removeInlineStyle(TextFormat.FORMAT_STRIKETHROUGH, start, end)
        inlineFormatter.removeInlineStyle(TextFormat.FORMAT_UNDERLINE, start, end)
        inlineFormatter.removeInlineStyle(TextFormat.FORMAT_CODE, start, end)
    }


    fun removeBlockStylesFromRange(start: Int, end: Int, ignoreLineBounds: Boolean = false) {
        blockFormatter.removeBlockStyle(start, end, Arrays.asList(AztecBlockSpan::class.java), ignoreLineBounds)
    }

    //logic party copied from TextView
    override fun onTextContextMenuItem(id: Int): Boolean {
        var min = 0
        var max = text.length

        if (isFocused) {
            min = Math.max(0, Math.min(selectionStart, selectionEnd))
            max = Math.max(0, Math.max(selectionStart, selectionEnd))
        }

        when (id) {
            android.R.id.paste -> paste(text, min, max)
            android.R.id.copy -> {
                copy(text, min, max)
                clearFocus() //hide text action menu
            }
            android.R.id.cut -> {
                copy(text, min, max)
                text.delete(min, max) //this will hide text action menu
            }
            else -> return super.onTextContextMenuItem(id)
        }

        return true
    }

    //Convert selected text to html and add it to clipboard
    fun copy(editable: Editable, start: Int, end: Int) {
        val selectedText = editable.subSequence(start, end)
        val parser = AztecParser()
        val output = SpannableStringBuilder(selectedText)

        //Strip block elements untill we figure out copy paste completely
        output.getSpans(0, output.length, ParagraphStyle::class.java).forEach { output.removeSpan(it) }
        clearMetaSpans(output)
        parser.syncVisualNewlinesOfBlockElements(output)
        val html = Format.removeSourceEditorFormatting(parser.toHtml(output))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.primaryClip = ClipData.newPlainText(null, html)
    }

    //copied from TextView with some changes
    private fun paste(editable: Editable, min: Int, max: Int) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null) {
            val parser = AztecParser()

            for (i in 0..clip.itemCount - 1) {
                val textToPaste = clip.getItemAt(i).coerceToText(context)

                val builder = SpannableStringBuilder()
                builder.append(parser.fromHtml(Format.removeSourceEditorFormatting(textToPaste.toString()), onMediaTappedListener,
                        this, context).trim())
                Selection.setSelection(editable, max)

                disableTextChangedListener()
                editable.replace(min, max, builder)
                enableTextChangedListener()

                inlineFormatter.joinStyleSpans(0, editable.length) //TODO: see how this affects performance
            }
        }
    }

    fun clearMetaSpans(text: Spannable) {
        BaseInputConnection.removeComposingSpans(text)
        text.getSpans(0, text.length, SuggestionSpan::class.java).forEach { text.removeSpan(it) }
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)

        if (visibility == View.VISIBLE) {
            requestFocus()
        }
    }

    fun link(url: String, anchor: String) {
        if (TextUtils.isEmpty(url) && linkFormatter.isUrlSelected()) {
            removeLink()
        } else if (linkFormatter.isUrlSelected()) {
            linkFormatter.editLink(url, anchor, linkFormatter.getUrlSpanBounds().first, linkFormatter.getUrlSpanBounds().second)
        } else {
            linkFormatter.addLink(url, anchor, selectionStart, selectionEnd)
        }
    }


    fun removeLink() {
        val urlSpanBounds = linkFormatter.getUrlSpanBounds()

        linkFormatter.linkInvalid(urlSpanBounds.first, urlSpanBounds.second)
        onSelectionChanged(urlSpanBounds.first, urlSpanBounds.second)
    }

    fun showLinkDialog(presetUrl: String = "", presetAnchor: String = "") {
        val urlAndAnchor = linkFormatter.getSelectedUrlWithAnchor()

        val url = if (TextUtils.isEmpty(presetUrl)) urlAndAnchor.first else presetUrl
        val anchor = if (TextUtils.isEmpty(presetAnchor)) urlAndAnchor.second else presetAnchor

        val builder = AlertDialog.Builder(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_link, null)

        val urlInput = dialogView.findViewById(R.id.linkURL) as EditText
        val anchorInput = dialogView.findViewById(R.id.linkText) as EditText

        urlInput.setText(url)
        anchorInput.setText(anchor)

        builder.setView(dialogView)
        builder.setTitle(R.string.link_dialog_title)

        builder.setPositiveButton(R.string.link_dialog_button_ok, { dialog, which ->
            val linkText = urlInput.text.toString().trim { it <= ' ' }
            val anchorText = anchorInput.text.toString().trim { it <= ' ' }

            link(linkText, anchorText)

        })

        if (linkFormatter.isUrlSelected()) {
            builder.setNeutralButton(R.string.link_dialog_button_remove_link, { dialogInterface, i ->
                removeLink()
            })
        }

        builder.setNegativeButton(R.string.link_dialog_button_cancel, { dialogInterface, i ->
            dialogInterface.dismiss()
        })

        addLinkDialog = builder.create()
        addLinkDialog!!.show()
    }

    fun showBlockEditorDialog(unknownHtmlSpan: UnknownHtmlSpan, html: String = "") {
        val builder = AlertDialog.Builder(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_block_editor, null)
        val source = dialogView.findViewById(R.id.source) as SourceViewEditText

        var editHtml = html
        if (TextUtils.isEmpty(editHtml)) {
            editHtml = unknownHtmlSpan.rawHtml.toString()
        }

        source.displayStyledAndFormattedHtml(editHtml)
        builder.setView(dialogView)

        builder.setPositiveButton(R.string.block_editor_dialog_button_save, { dialog, which ->
            val spanStart = text.getSpanStart(unknownHtmlSpan)

            val textBuilder = SpannableStringBuilder()
            textBuilder.append(AztecParser().fromHtml(source.getPureHtml(), onMediaTappedListener, this, context).trim())
            setSelection(spanStart)

            disableTextChangedListener()

            text.removeSpan(unknownHtmlSpan)
            text.replace(spanStart, spanStart + 1, textBuilder)

            val unknownClickSpan = text.getSpans(spanStart, spanStart + 1, UnknownClickableSpan::class.java).firstOrNull()
            if (unknownClickSpan != null) {
                text.removeSpan(unknownClickSpan)
            }

            enableTextChangedListener()

            inlineFormatter.joinStyleSpans(0, text.length)
        })

        builder.setNegativeButton(R.string.block_editor_dialog_button_cancel, { dialogInterface, i ->
            dialogInterface.dismiss()
        })

        unknownBlockSpanStart = text.getSpanStart(unknownHtmlSpan)
        blockEditorDialog = builder.create()
        blockEditorDialog!!.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        blockEditorDialog!!.show()
    }

    //Custom input connection is used to detect the press of backspace when no characters are deleted
    //(eg. at 0 index of EditText)
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        return AztecInputConnection(super.onCreateInputConnection(outAttrs), true)
    }

    private inner class AztecInputConnection(target: InputConnection, mutable: Boolean) : InputConnectionWrapper(target, mutable) {

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action === KeyEvent.ACTION_DOWN && event.keyCode === KeyEvent.KEYCODE_DEL) {
                history.beforeTextChanged(toFormattedHtml())

                inlineFormatter.tryRemoveLeadingInlineStyle()
                val isStyleRemoved = blockFormatter.tryRemoveBlockStyleFromFirstLine()
                if (isStyleRemoved) {
                    history.handleHistory(this@AztecText)
                    return false
                }

            }
            return super.sendKeyEvent(event)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
//            if (beforeLength == 1 && afterLength == 0) {
//                return sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) && sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
//            }

            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    fun insertMedia(drawable: Drawable?, attributes: Attributes) {
        lineBlockFormatter.insertMedia(drawable, attributes, onMediaTappedListener)
    }

    fun removeMedia(attributePredicate: AttributePredicate) {
        text.getSpans(0, text.length, AztecMediaSpan::class.java).forEach {
            if (it.attributes != null) {
                if (attributePredicate.matches(it.attributes as Attributes)) {
                    val start = text.getSpanStart(it)
                    val end = text.getSpanEnd(it)

                    val clickableSpan = text.getSpans(start, end, AztecMediaClickableSpan::class.java).firstOrNull()

                    text.removeSpan(clickableSpan)
                    text.removeSpan(it)

                    text.delete(start, end)
                }
            }
        }
    }

    interface AttributePredicate {
        /**
         * Return true if the attributes list fulfills some condition
         */
        fun matches(attrs: Attributes): Boolean
    }

    fun setOverlayLevel(attributePredicate: AttributePredicate, index: Int, level: Int, attrs: Attributes) {
        text.getSpans(0, text.length, AztecMediaSpan::class.java).forEach {
            if (it.attributes != null) {
                if (attributePredicate.matches(it.attributes as Attributes)) {
                    it.setOverayLevel(index, level)
                    it.attributes = attrs
                }
            }
        }
    }

    fun setOverlay(attributePredicate: AttributePredicate, index: Int, overlay: Drawable?, gravity: Int,
                   attributes: Attributes?) {
        text.getSpans(0, text.length, AztecMediaSpan::class.java).forEach {
            if (it.attributes != null) {
                if (attributePredicate.matches(it.attributes as Attributes)) {
                    // set the new overlay drawable
                    it.setOverlay(index, overlay, gravity)

                    if (attributes != null) {
                        it.attributes = attributes
                    }

                    invalidate()
                }
            }
        }
    }

    fun clearOverlays(attributePredicate: AttributePredicate, attributes: Attributes?) {
        text.getSpans(0, text.length, AztecMediaSpan::class.java).forEach {
            if (it.attributes != null) {
                if (attributePredicate.matches(it.attributes as Attributes)) {
                    it.clearOverlays()

                    if (attributes != null) {
                        it.attributes = attributes
                    }

                    invalidate()
                }
            }
        }
    }

    fun getMediaAttributes(attributePredicate: AttributePredicate): Attributes? {
        return getAllMediaAttributes(attributePredicate).firstOrNull()
    }

    fun getAllMediaAttributes(attributePredicate: AttributePredicate): List<Attributes?> {
        return text
                .getSpans(0, text.length, AztecMediaSpan::class.java)
                .filter {
                    if (it.attributes != null) {
                        return@filter attributePredicate.matches(it.attributes as Attributes)
                    } else {
                        return@filter false
                    }
                }
                .map { it.attributes }
    }

    override fun onUnknownHtmlClicked(unknownHtmlSpan: UnknownHtmlSpan) {
        showBlockEditorDialog(unknownHtmlSpan)
    }
}
