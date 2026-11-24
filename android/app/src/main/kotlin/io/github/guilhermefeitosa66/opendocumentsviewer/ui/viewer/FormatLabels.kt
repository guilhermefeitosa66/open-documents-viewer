package io.github.guilhermefeitosa66.opendocumentsviewer.ui.viewer

import android.content.Context
import io.github.guilhermefeitosa66.opendocumentsviewer.R
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.CardModel
import io.github.guilhermefeitosa66.opendocumentsviewer.markdown.MailModel

/** Words the format models put into documents (field names), in the app's language. Set by the activity. */
object FormatLabels {
    var mail = MailModel.Labels("From", "To", "Cc", "Date", "Attachments")
        private set
    var card = CardModel.CardLabels("Phone", "Email", "Address", "Organization", "Title", "Birthday", "Website", "Note", "No name")
        private set
    var calendar = CardModel.CalLabels("Where", "Organizer", "Repeats", "task", "(no title)", "all day")
        private set

    fun load(c: Context) {
        mail = MailModel.Labels(c.getString(R.string.mail_from), c.getString(R.string.mail_to), c.getString(R.string.mail_cc), c.getString(R.string.mail_date), c.getString(R.string.mail_attachments))
        card = CardModel.CardLabels(
            c.getString(R.string.card_phone), c.getString(R.string.card_email), c.getString(R.string.card_address),
            c.getString(R.string.card_organization), c.getString(R.string.card_title), c.getString(R.string.card_birthday),
            c.getString(R.string.card_website), c.getString(R.string.card_note), c.getString(R.string.card_unnamed),
        )
        calendar = CardModel.CalLabels(
            c.getString(R.string.cal_location), c.getString(R.string.cal_organizer), c.getString(R.string.cal_repeats),
            c.getString(R.string.cal_task), c.getString(R.string.cal_untitled), c.getString(R.string.cal_all_day),
        )
    }
}
