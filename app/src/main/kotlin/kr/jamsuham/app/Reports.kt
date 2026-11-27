package kr.jamsuham.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 신고: 부적절한 사용자나 채팅을 개발자에게 알린다.
 * 앱이 스스로 보내는 것은 없다 — 신고 내용을 채운 **메일 작성 화면**을 열고, 이용자가 자기 메일 앱에서 보낸다
 * (받는 주소 [AppInfo.CONTACT_EMAIL]). 메일 앱이 없으면 내용을 복사하고 주소를 보여 준다.
 * 개발자는 서버의 사용자를 제재할 수 없으므로, 서버 운영진에게도 신고하라고 안내한다.
 */
object Reports {

    /**
     * 신고 창을 연다. [name]·[uuid] 는 신고할 사람(모르면 null), [chatLine] 은 신고할 채팅(접속자 목록에서 신고하면 null).
     * "이 사용자도 차단"을 켜 두고 보내면 차단하고 [onBlocked] 를 부른다.
     */
    fun show(activity: Activity, ui: Ui, serverId: String, name: String?, uuid: UUID?, chatLine: String?, onBlocked: () -> Unit = {}) {
        val reasons = listOf(
            tr("욕설·괴롭힘", "Abuse or harassment"),
            tr("스팸·광고", "Spam or ads"),
            tr("부적절한 닉네임·내용", "Inappropriate name or content"),
            tr("기타", "Other"),
        )
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        if (name != null) box.addView(ui.text(tr("신고할 사용자: $name", "User: $name"), 14f, bold = true))
        if (chatLine != null) box.addView(ui.text(chatLine.take(300), 13f, ui.p.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        box.addView(ui.label(tr("사유", "Reason")))
        val group = RadioGroup(activity)
        val buttons = reasons.mapIndexed { i, reason ->
            RadioButton(activity).apply {
                id = i + 1
                text = reason
                setTextColor(ui.p.text)
                group.addView(this)
            }
        }
        group.check(buttons.first().id)
        box.addView(group)
        val details = ui.input(tr("설명 (선택)", "Details (optional)"), singleLine = false)
        box.addView(details, ui.gap(6))
        val alsoBlock = CheckBox(activity).apply {
            text = tr("이 사용자도 차단", "Also block this user")
            setTextColor(ui.p.text)
            isChecked = true
        }
        if (name != null) box.addView(alsoBlock, ui.gap(6))
        box.addView(ui.hint(tr(
            "이 앱은 서버의 사용자를 제재할 수 없습니다. 서버 운영진에게도 신고해 주세요.",
            "This app can't take action against players on a server. Please report to the server staff too.",
        )))
        box.addView(ui.hint(tr(
            "'신고 보내기'를 누르면 내용이 채워진 메일 작성 화면이 열립니다. 메일을 보내야 신고가 전달됩니다.",
            "'Send report' opens an email draft with the report filled in. The report is delivered when you send that email.",
        )))
        AlertDialog.Builder(activity)
            .setTitle(tr("신고", "Report"))
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(tr("신고 보내기", "Send report")) { _, _ ->
                val server = Store(activity).server(serverId)
                val reason = reasons.getOrElse(group.checkedRadioButtonId - 1) { reasons.last() }
                val text = body(
                    server = if (server == null) "?" else if (server.demo) "demo" else server.address,
                    name = name,
                    uuid = uuid?.toString(),
                    reason = reason,
                    details = details.text.toString().trim(),
                    chatLine = chatLine,
                    version = Updater.versionLabel(activity) + " (" + Updater.currentBuild(activity) + (if (Flavor.PLAY) ", Play)" else ", GitHub)"),
                    timeMs = System.currentTimeMillis(),
                )
                val subject = tr("[잠수크래프트 신고] ", "[Jamsucraft report] ") + (name ?: chatLine?.take(40) ?: "")
                send(activity, subject, text)
                if (name != null && alsoBlock.isChecked) {
                    BlockList.add(name, uuid)
                    onBlocked()
                }
            }
            .setNegativeButton(tr("취소", "Cancel"), null)
            .show()
    }

    /** 신고 메일의 본문 (한 줄에 하나씩) */
    fun body(server: String, name: String?, uuid: String?, reason: String, details: String, chatLine: String?, version: String, timeMs: Long): String {
        val lines = ArrayList<String>()
        lines += tr("신고 대상 닉네임: ", "Reported nickname: ") + (name ?: tr("(모름)", "(unknown)"))
        if (uuid != null) lines += "UUID: $uuid"
        lines += tr("서버: ", "Server: ") + server
        lines += tr("사유: ", "Reason: ") + reason
        if (details.isNotEmpty()) lines += tr("설명: ", "Details: ") + details
        if (chatLine != null) lines += tr("채팅 내용: ", "Message: ") + chatLine
        lines += tr("시각: ", "Time: ") + SimpleDateFormat("yyyy-MM-dd HH:mm Z", Locale.US).format(Date(timeMs))
        lines += tr("앱 버전: ", "App version: ") + version
        return lines.joinToString("\n")
    }

    /** 메일 작성 화면을 연다. 메일 앱이 없으면 내용을 복사하고 보낼 주소를 알려 준다 */
    private fun send(activity: Activity, subject: String, text: String) {
        val address = AppInfo.CONTACT_EMAIL
        val uri = Uri.parse("mailto:" + address + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(text))
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            activity.startActivity(intent)
        } catch (_: Throwable) {
            try {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(subject, subject + "\n" + text))
            } catch (_: Throwable) {
            }
            AlertDialog.Builder(activity)
                .setTitle(tr("신고", "Report"))
                .setMessage(tr("메일 앱이 없어 신고 내용을 복사했습니다. 아래 주소로 보내 주세요:\n$address", "No mail app found, so the report was copied. Please send it to:\n$address"))
                .setPositiveButton(tr("확인", "OK"), null)
                .show()
        }
    }
}
