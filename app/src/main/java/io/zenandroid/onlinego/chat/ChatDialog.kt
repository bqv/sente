package io.zenandroid.onlinego.chat

import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v7.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.reactivex.subjects.PublishSubject
import io.zenandroid.onlinego.R
import io.zenandroid.onlinego.model.local.Message
import kotlinx.android.synthetic.main.dialog_messages.*

class ChatDialog : DialogFragment() {

    private val messagesAdapter = MessagesAdapter()
    private val newMessageSubject = PublishSubject.create<String>()

    val newMessages = newMessageSubject.hide()

    fun setMessages(messages: List<Message>) {
        messagesAdapter.setMessageList(messages)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
        isCancelable = true
        setStyle(DialogFragment.STYLE_NORMAL, 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog.setTitle("Game chat")
        return inflater.inflate(R.layout.dialog_messages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        messagesRecycler.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = messagesAdapter
            smoothScrollToPosition(messagesAdapter.itemCount)
        }

        sendButton.setOnClickListener {
            if(!newMessage.text.isNullOrEmpty()) {
                newMessageSubject.onNext(newMessage.text.toString())
            }
            newMessage.setText("")
        }
    }

    fun setChatMyId(myId: Long?) {
        messagesAdapter.setMyId(myId)
    }

    override fun onResume() {
        super.onResume()
        val params = dialog.window!!.attributes
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        dialog.window!!.attributes = params as android.view.WindowManager.LayoutParams
    }
}