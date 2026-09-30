package io.github.illagercpr.landrop.notify

/**
 * 「界面是否正对着用户」。
 *
 * 只服务于一个判断：该不该发通知。用户正盯着聊天页时又弹一条新消息通知，
 * 除了多响一声没有任何作用。
 *
 * 用 Activity 的 onStart/onStop 自己维护，而不是引入 ProcessLifecycleOwner：
 * 本应用只有一个 Activity，onStop 的语义恰好就是「不再可见」（息屏、切后台、
 * 被别的应用盖住），粒度正好；少一个依赖也少一处需要解释的行为差异。
 */
object AppVisibility {

    /** 由 [io.github.illagercpr.landrop.MainActivity] 在 onStart/onStop 里维护。 */
    @Volatile
    var foreground: Boolean = false
}
