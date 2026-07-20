package com.jarvis.agent.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.agent.R
import com.jarvis.agent.llm.ChatMessage

/**
 * RecyclerView Adapter for the chat message list.
 * Shows user messages right-aligned (blue) and assistant messages left-aligned (dark).
 */
class ChatAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
    }

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val bubbleBackground: View = view.findViewById(R.id.bubbleBackground)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.content

        val isUser = message.role == "user"
        val params = holder.bubbleBackground.layoutParams as FrameLayout.LayoutParams

        if (isUser) {
            params.gravity = Gravity.END
            holder.bubbleBackground.setBackgroundResource(R.drawable.bg_message_user)
            holder.messageText.setTextColor(0xFFFFFFFF.toInt())
        } else {
            params.gravity = Gravity.START
            holder.bubbleBackground.setBackgroundResource(R.drawable.bg_message_assistant)
            holder.messageText.setTextColor(0xFFE0E0E0.toInt())
        }
        holder.bubbleBackground.layoutParams = params
    }

    override fun getItemCount() = messages.size

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].role == "user") TYPE_USER else TYPE_ASSISTANT
    }
}
