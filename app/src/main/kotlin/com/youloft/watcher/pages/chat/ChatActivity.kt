package com.youloft.watcher.pages.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.youloft.watcher.databinding.ActivityChatBinding

/**
 * The chat screen. The shell is the recovered `activity_chat.xml` — title bar, back, detail entry, the
 * `ChatPhoneStausView` the original hung under it — hosting the message fragment in `fl_fragment`, the
 * same container the original's chat fragment sat in.
 *
 * The message surface inside is this build's own minimal list, because the original's rows were HuanXin
 * EaseUI layouts and that SDK is one of the things the source-tree loss made unrecoverable; the
 * deviation is documented in DESIGN (五点三十一) rather than faked with a lookalike of a library the
 * project cannot ship. Data comes only from the [com.youloft.linkup.im.ImClient] seam — no OpenIM type
 * crosses into this package.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvTitle.text = intent.getStringExtra(EXTRA_PEER_NAME) ?: intent.getStringExtra(EXTRA_PEER_ID)
        binding.ivBack.setOnClickListener { finish() }
        // The detail entry belongs to the friend-info card's chat tab; until that screen exists the
        // button stays visible but inert rather than navigating somewhere fake.
        binding.ivDetail.setOnClickListener { }

        if (supportFragmentManager.findFragmentById(binding.flFragment.id) == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    binding.flFragment.id,
                    ChatMessagesFragment().apply arguments@{
                        arguments = Bundle().apply {
                            putLong(ChatMessagesFragment.EXTRA_PEER_ID, intent.getLongExtra(EXTRA_PEER_ID, 0L))
                            putString(ChatMessagesFragment.EXTRA_PEER_NAME, intent.getStringExtra(EXTRA_PEER_NAME))
                        }
                    },
                )
                .commit()
        }
    }

    companion object {
        const val EXTRA_PEER_ID = "peerUserId"
        const val EXTRA_PEER_NAME = "peerName"

        fun start(context: Context, peerUserId: Long, peerName: String?) {
            context.startActivity(
                Intent(context, ChatActivity::class.java)
                    .putExtra(EXTRA_PEER_ID, peerUserId)
                    .putExtra(EXTRA_PEER_NAME, peerName),
            )
        }
    }
}
