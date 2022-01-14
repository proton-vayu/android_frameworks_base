package com.android.systemui.statusbar.notification.stack

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotificationHeadsUpLog
import javax.inject.Inject

class StackStateLogger @Inject constructor(
    @NotificationHeadsUpLog private val buffer: LogBuffer
) {
    fun logHUNViewDisappearing(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
        }, {
            "Heads up view disappearing $str1 "
        })
    }

    fun logHUNViewAppearing(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
        }, {
            "Heads up notification view appearing $str1 "
        })
    }

    fun disappearAnimationEnded(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
        }, {
            "Heads up notification disappear animation ended $str1 "
        })
    }

    fun appearAnimationEnded(key: String) {
        buffer.log(TAG, LogLevel.INFO, {
            str1 = key
        }, {
            "Heads up notification appear animation ended $str1 "
        })
    }
}

private const val TAG = "StackScroll"