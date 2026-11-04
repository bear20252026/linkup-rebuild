package com.youloft.watcher.pages.friend

import com.youloft.linkup.api.dto.Permission

/**
 * The four dialogs this screen opens. None of them is rebuilt yet, so the default implementation
 * never invokes a callback; screens and tests drive the same method shape, and each dialog gets a
 * real implementation when its own card lands.
 */
interface FriendInfoDialogs {
    fun confirmDelete(message: String, onConfirm: () -> Unit)

    fun updateRelation(current: Int, onPicked: (Int) -> Unit)

    fun updateRemark(title: String, current: String, hint: String, onSubmit: (String) -> Unit)

    fun permissionSettings(current: Permission, onResult: (Permission?) -> Unit)
}

/** `ConfirmDialog`, `UpdateRelationDialog`, `UpdateUsernameDialog`, `FriendPermissionsSettingsDialog`. */
object NoopFriendInfoDialogs : FriendInfoDialogs {
    override fun confirmDelete(message: String, onConfirm: () -> Unit) = Unit

    override fun updateRelation(current: Int, onPicked: (Int) -> Unit) = Unit

    override fun updateRemark(title: String, current: String, hint: String, onSubmit: (String) -> Unit) = Unit

    override fun permissionSettings(current: Permission, onResult: (Permission?) -> Unit) = Unit
}
