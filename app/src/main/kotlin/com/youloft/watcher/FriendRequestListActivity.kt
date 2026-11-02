package com.youloft.watcher

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.youloft.linkup.api.dto.FriendRequest
import com.youloft.linkup.net.LinkUpClient
import kotlinx.coroutines.launch

/** Rebuilt from the recovered 1.6.6 layout + the recovered `/api/relation/apply_list` contract. */
class FriendRequestListActivity : AppCompatActivity() {

    private val applications = mutableListOf<FriendRequest?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friend_request_list)

        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }

        val list = findViewById<RecyclerView>(R.id.recyclerView)
        list.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            val response = runCatching {
                LinkUpClient(context = AndroidDeviceContext(this@FriendRequestListActivity))
                    .api.apiRelationApplyList(page = 1, pageSize = 20)
            }.getOrNull()

            applications.clear()
            response?.data?.list?.let { applications.addAll(it) }
            findViewById<View>(R.id.emptyView).visibility =
                if (applications.isEmpty()) View.VISIBLE else View.GONE
        }
    }
}
