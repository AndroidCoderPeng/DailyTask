package com.pengxh.daily.app.extensions

import android.app.Activity
import android.view.View
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Activity.showCustomDialog(
    @LayoutRes resId: Int,
    cancelable: Boolean = true,
    onViewCreated: (View, AlertDialog) -> Unit = { _, _ -> }
): AlertDialog {
    val content = layoutInflater.inflate(resId, null, false)
    val dialog = MaterialAlertDialogBuilder(this)
        .setView(content)
        .setCancelable(cancelable)
        .create()
    onViewCreated(content, dialog)
    dialog.show()
    return dialog
}

fun Activity.showActionSheet(
    @LayoutRes resId: Int,
    cancelable: Boolean = true,
    onViewCreated: (View, BottomSheetDialog) -> Unit = { _, _ -> }
): BottomSheetDialog {
    val content = layoutInflater.inflate(resId, null, false)
    val dialog = BottomSheetDialog(this).apply {
        setContentView(content)
        setCancelable(cancelable)
    }
    onViewCreated(content, dialog)
    dialog.show()
    return dialog
}