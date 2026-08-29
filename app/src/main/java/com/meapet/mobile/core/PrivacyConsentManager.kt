package com.meapet.mobile.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.meapet.mobile.settings.appDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 隐私授权管理器。
 *
 * 管理用户是否已同意《隐私政策》中关于友盟统计 SDK 数据采集的授权。
 * - 首次启动：未同意，App 正常使用但不初始化友盟 SDK。
 * - 同意后：友盟 SDK 正式初始化，开始采集并上报数据。
 * - 取消授权后：停止上报（后续冷启动不再 init），App 其余功能不受影响。
 *
 * 是否需要弹窗由隐私政策版本号（DataStore `privacy_version_shown`）决定，
 * 见 SettingsManager / MainActivity；此处只负责授权状态本身的读写。
 *
 * 使用 SharedPreferences 做同步读写，保证 [isAgreed] 可在 Application.onCreate 中同步判断。
 */
object PrivacyConsentManager {

    private const val TAG = "PrivacyConsentManager"
    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_AGREED = "umeng_privacy_agreed"

    /** DataStore 同步写入的超时上限（毫秒）：授权弹窗点击路径，给磁盘繁忙留余量但防无限阻塞。 */
    private const val DATASTORE_WRITE_TIMEOUT_MS = 2000L

    private val KEY_DS_AGREED = booleanPreferencesKey("umeng_privacy_agreed")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户是否已同意隐私授权（同步读取，可在主线程调用）。 */
    fun isAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /** 授权状态 Flow（响应式订阅）。 */
    fun agreedFlow(context: Context): Flow<Boolean> =
        context.appDataStore.data.map { it[KEY_DS_AGREED] ?: false }

    /** 标记用户已做出选择并记录授权状态。 */
    @Suppress("ApplySharedPref")
    fun setAgreed(context: Context, agreed: Boolean) {
        // 用同步 commit() 保证写盘完成后再返回：取消授权后会立刻 killProcess，
        // apply() 的异步落盘可能来不及，导致重启后仍读到旧的授权状态。
        prefs(context).edit()
            .putBoolean(KEY_AGREED, agreed)
            .commit()
        // 同步写入 DataStore 供 Flow 订阅。此调用发生在用户点击授权弹窗按钮时
        // （非冷启动热路径），加超时上限避免异常情况下无限阻塞主线程；
        // 授权状态以上面的 SharedPreferences 为准，DataStore 超时写失败不影响授权判断。
        try {
            runBlocking {
                withTimeout(DATASTORE_WRITE_TIMEOUT_MS) {
                    context.appDataStore.edit { it[KEY_DS_AGREED] = agreed }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DataStore 写入授权状态超时/失败（已忽略，以 SharedPreferences 为准）", e)
        }
    }
}
